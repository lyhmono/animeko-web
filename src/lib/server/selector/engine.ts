// Selector 引擎：CSS / JsonPath 解析搜索与剧集，静态页面分析解析播放地址。
import * as cheerio from 'cheerio';
import { badRequest } from '../gate';
import { fetchText } from '../http';
import { DEFAULT_UA, EpisodeHit, EpisodesResult, ResolvedVideo, StoredSource, SubjectHit } from './types';
import { acquireSourceSlot, recordHealth, singleFlight } from './store';
import { evalJsonPath } from './jsonPath';

function absolutize(link: string, base: string): string {
  try {
    return new URL(link, base).toString();
  } catch {
    return link;
  }
}

function safeRegex(pattern: string, flags = ''): RegExp {
  if (pattern.length > 500) badRequest('正则表达式过长');
  try {
    return new RegExp(pattern, flags);
  } catch {
    badRequest('正则表达式非法');
  }
}

function applyLinkTemplate(link: string, template: string | null | undefined, base: string): string {
  const resolved = template ? template.replaceAll('{value}', link) : link;
  return absolutize(resolved, base);
}

function baseFor(configured: string | undefined, finalUrl: string): string {
  return configured && configured.trim() ? configured : new URL(finalUrl).origin;
}

function parseSort(name: string, pattern: string | undefined): { sort: string; sortNum: number | null } {
  let sort = name.trim();
  if (pattern) {
    try {
      const m = safeRegex(pattern).exec(name);
      const ep = m?.groups?.ep;
      if (ep) sort = ep.trim();
    } catch {
      // 忽略非法正则，退回原始名称
    }
  }
  const num = Number(sort);
  if (Number.isFinite(num) && sort !== '') return { sort, sortNum: num };
  if (/正片|高清版|720P|1080P|2K|4K/i.test(sort)) return { sort, sortNum: 1 };
  return { sort, sortNum: null };
}

function extractCoverFromElement($el: cheerio.Cheerio<any>, base: string): string | null {
  const img = $el.find('img').first();
  const src = img.attr('data-src') ?? img.attr('data-original') ?? img.attr('src');
  return src ? absolutize(src, base) : null;
}

async function fetchSourcePage(source: StoredSource, url: string) {
  const cfg = source.arguments.searchConfig;
  await acquireSourceSlot(source.arguments.name, cfg.requestInterval ?? 3000);
  return fetchText(url, { headers: { 'user-agent': cfg.userAgent || DEFAULT_UA } });
}

/** 数据源搜索。 */
export async function searchSource(source: StoredSource, keyword: string): Promise<SubjectHit[]> {
  if (!keyword.trim()) badRequest('关键词不能为空');
  const cfg = source.arguments.searchConfig;
  const name = source.arguments.name;
  return singleFlight(`search:${name}:${keyword}`, async () => {
    const started = Date.now();
    try {
      const searchUrl = cfg.searchUrl.replaceAll('{keyword}', encodeURIComponent(keyword.trim()));
      const { text, finalUrl } = await fetchSourcePage(source, searchUrl);
      const base = baseFor(cfg.rawBaseUrl, finalUrl);
      const format = cfg.subjectFormatId ?? 'a';
      let hits: SubjectHit[] = [];
      if (format === 'json-path-indexed') {
        hits = parseJsonPathSearch(text, cfg, base);
      } else {
        const $ = cheerio.load(text);
        if (format === 'indexed') {
          const f = cfg.selectorSubjectFormatIndexed ?? {};
          const names = $(f.selectNames ?? '.search-box .thumb-content > .thumb-txt');
          const links = $(f.selectLinks ?? '.search-box .thumb-menu > a');
          names.each((i, el) => {
            const linkEl = links.eq(i);
            const href = linkEl.attr('href');
            const title = $(el).text().trim();
            if (!href || !title) return;
            hits.push({
              name: title,
              url: applyLinkTemplate(href, f.linkTemplate, base),
              cover: extractCoverFromElement($(el).parent(), base),
            });
          });
        } else {
          const f = cfg.selectorSubjectFormatA ?? {};
          $(f.selectLists ?? 'div.video-info-header > a').each((_, el) => {
            const $el = $(el);
            const href = $el.attr('href');
            if (!href) return;
            const title = ($el.attr('title') ?? '').trim() || $el.text().trim();
            if (!title) return;
            hits.push({
              name: title,
              url: applyLinkTemplate(href, f.linkTemplate, base),
              cover: extractCoverFromElement($el, base),
            });
          });
        }
      }
      recordHealth(name, true, Date.now() - started);
      return hits;
    } catch (err) {
      recordHealth(name, false, Date.now() - started, err instanceof Error ? err.message : '搜索失败');
      throw err;
    }
  });
}

function parseJsonPathSearch(text: string, cfg: StoredSource['arguments']['searchConfig'], base: string): SubjectHit[] {
  const f = cfg.selectorSubjectFormatJsonPathIndexed ?? {};
  let root: unknown;
  try {
    root = JSON.parse(text);
  } catch {
    badRequest('数据源未返回合法 JSON');
  }
  const namePath = f.selectNames ?? "$[*]['title','name']";
  const linkPath = f.selectLinks ?? "$[*]['url', 'link']";
  const names = evalJsonPath(root, namePath);
  const links = evalJsonPath(root, linkPath);
  const hits: SubjectHit[] = [];
  // 尝试从记录本身提取封面
  let records: Array<Record<string, unknown>> = [];
  if (Array.isArray(root)) records = root.filter((r): r is Record<string, unknown> => Boolean(r) && typeof r === 'object');
  for (let i = 0; i < Math.min(names.length, links.length); i += 1) {
    const title = String(names[i] ?? '').trim();
    const link = String(links[i] ?? '').trim();
    if (!title || !link) continue;
    const record = records[i];
    let cover: string | null = null;
    if (record) {
      for (const key of ['pic', 'cover', 'img', 'image', 'vod_pic', 'listthumb_20']) {
        const value = record[key];
        if (typeof value === 'string' && value) {
          cover = absolutize(value, base);
          break;
        }
      }
    }
    hits.push({ name: title, url: applyLinkTemplate(link, f.linkTemplate, base), cover });
  }
  return hits;
}

/** 数据源剧集列表。 */
export async function listEpisodes(source: StoredSource, subjectUrl: string): Promise<EpisodesResult> {
  const name = source.arguments.name;
  const started = Date.now();
  try {
    const cfg = source.arguments.searchConfig;
    const { text, finalUrl } = await fetchSourcePage(source, subjectUrl);
    const base = baseFor(cfg.rawBaseUrl, finalUrl);
    const $ = cheerio.load(text);
    let result: EpisodesResult;
    if ((cfg.channelFormatId ?? 'no-channel') === 'index-grouped') {
      const f = cfg.selectorChannelFormatFlattened ?? {};
      const channelNames = $(f.selectChannelNames ?? '.anthology-tab > .swiper-wrapper a');
      const lists = $(f.selectEpisodeLists ?? '.anthology-list-box');
      const groups: Array<{ name: string; episodes: EpisodeHit[] }> = [];
      const channels: string[] = [];
      lists.each((i, listEl) => {
        let channel = channelNames.eq(i).text().trim() || `线路 ${i + 1}`;
        if (f.matchChannelName) {
          try {
            const m = safeRegex(f.matchChannelName).exec(channel);
            if (m?.groups?.ch) channel = m.groups.ch.trim();
          } catch { /* 保留原名 */ }
        }
        channels.push(channel);
        const episodes: EpisodeHit[] = [];
        const linkSelector = f.selectEpisodeLinksFromList;
        const links = linkSelector ? $(listEl).find(linkSelector) : null;
        $(listEl).find(f.selectEpisodesFromList ?? 'a').each((j, epEl) => {
          const $ep = $(epEl);
          const epName = ($ep.attr('title') ?? '').trim() || $ep.text().trim();
          const href = links ? links.eq(j).attr('href') : $ep.attr('href');
          if (!epName || !href) return;
          const { sort, sortNum } = parseSort(epName, f.matchEpisodeSortFromName);
          episodes.push({ channel, name: epName, sort, sortNum, playUrl: absolutize(href, base) });
        });
        groups.push({ name: channel, episodes });
      });
      result = { channels, groups, episodes: groups.flatMap((g) => g.episodes) };
    } else {
      const f = cfg.selectorChannelFormatNoChannel ?? {};
      const episodes: EpisodeHit[] = [];
      const linkSelector = f.selectEpisodeLinks;
      const links = linkSelector ? $(linkSelector) : null;
      $(f.selectEpisodes ?? '#glist-1 a').each((i, el) => {
        const $el = $(el);
        const epName = ($el.attr('title') ?? '').trim() || $el.text().trim();
        const href = links ? links.eq(i).attr('href') : $el.attr('href');
        if (!epName || !href) return;
        const { sort, sortNum } = parseSort(epName, f.matchEpisodeSortFromName);
        episodes.push({ channel: null, name: epName, sort, sortNum, playUrl: absolutize(href, base) });
      });
      result = { channels: [], groups: [], episodes };
    }
    recordHealth(name, true, Date.now() - started);
    return result;
  } catch (err) {
    recordHealth(name, false, Date.now() - started, err instanceof Error ? err.message : '剧集解析失败');
    throw err;
  }
}

/** 从页面收集候选 URL（iframe / video / source / 绝对地址），并做简单反转义。 */
function pageCandidates(html: string, base: string): { iframes: string[]; urls: string[] } {
  const unescaped = html
    .replace(/\\u([0-9a-fA-F]{4})/g, (_, hex) => String.fromCharCode(parseInt(hex, 16)))
    .replace(/\\x([0-9a-fA-F]{2})/g, (_, hex) => String.fromCharCode(parseInt(hex, 16)))
    .replace(/\\\//g, '/');
  const $ = cheerio.load(unescaped);
  const iframes: string[] = [];
  $('iframe[src]').each((_, el) => {
    const src = $(el).attr('src');
    if (src) iframes.push(absolutize(src, base));
  });
  const urls = new Set<string>();
  $('video[src], source[src], a[href]').each((_, el) => {
    const src = $(el).attr('src') ?? $(el).attr('href');
    if (src) urls.add(absolutize(src, base));
  });
  for (const m of unescaped.matchAll(/https?:\/\/[^\s"'<>\\)]+/g)) urls.add(m[0]);
  return { iframes, urls: [...urls] };
}

function findVideoInPage(html: string, finalUrl: string, matchVideoUrl: string | undefined): string | null {
  const { urls } = pageCandidates(html, finalUrl);
  if (matchVideoUrl) {
    try {
      const re = safeRegex(matchVideoUrl);
      const direct = re.exec(html);
      if (direct) {
        const raw = (direct[1] ?? direct[0]).replace(/\\\//g, '/');
        return absolutize(raw, finalUrl);
      }
      for (const url of urls) {
        if (re.test(url)) return url;
      }
    } catch {
      // 配置正则非法时走通用回退
    }
  }
  for (const url of urls) {
    if (/\.m3u8(\?|$)/i.test(url)) return url;
  }
  for (const url of urls) {
    if (/\.(mp4|mkv|flv)(\?|$)/i.test(url)) return url;
  }
  return null;
}

/** 解析播放地址（最多 5 层嵌套页面，总预算 30 秒）。 */
export async function resolveVideo(source: StoredSource, playUrl: string): Promise<ResolvedVideo> {
  const name = source.arguments.name;
  const started = Date.now();
  try {
    const cfg = source.arguments.searchConfig;
    const mv = cfg.matchVideo ?? {};
    const ua = mv.addHeadersToVideo?.userAgent || cfg.userAgent || DEFAULT_UA;
    let currentUrl = playUrl;
    let videoUrl: string | null = null;
    let finalPageUrl = playUrl;
    for (let depth = 0; depth < 5; depth += 1) {
      if (Date.now() - started > 30000) break;
      const { text, finalUrl } = await fetchText(currentUrl, { headers: { 'user-agent': ua } });
      finalPageUrl = finalUrl;
      videoUrl = findVideoInPage(text, finalUrl, mv.matchVideoUrl);
      if (videoUrl) break;
      if (mv.enableNestedUrl === false) break;
      const { iframes } = pageCandidates(text, finalUrl);
      const nestedPattern = mv.matchNestedUrl ? safeRegex(mv.matchNestedUrl) : /^.+(m3u8|vip|xigua\.php).+\?/;
      const next = iframes.find((u) => nestedPattern.test(u));
      if (!next || next === currentUrl) break;
      currentUrl = next;
    }
    if (!videoUrl) {
      const err = new Error('NO_VIDEO_URL_FOUND');
      (err as Error & { status?: number }).status = 502;
      throw err;
    }
    const cookie = (mv.cookies ?? '')
      .split('\n').map((line) => line.trim()).filter(Boolean).join('; ');
    const referer = mv.addHeadersToVideo?.referer || new URL(finalPageUrl).origin;
    const result: ResolvedVideo = {
      url: videoUrl,
      referer,
      userAgent: ua,
      cookie,
      cookieHosts: mv.cookieHosts ?? [],
    };
    recordHealth(name, true, Date.now() - started);
    return result;
  } catch (err) {
    recordHealth(name, false, Date.now() - started, err instanceof Error ? err.message : '播放解析失败');
    throw err;
  }
}
