"""构建及冒烟配置的可重复性契约；不启动服务、不调用外网。"""
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


class BuildContractTest(unittest.TestCase):
    def test_gradle_distribution_is_checksum_pinned(self):
        text = (ROOT / 'backend/gradle/wrapper/gradle-wrapper.properties').read_text()
        self.assertRegex(text, r'(?m)^distributionSha256Sum=[0-9a-f]{64}$')

    def test_docker_gradle_matches_the_wrapper_version(self):
        """镜像里跑的是基础镜像自带的 gradle（.dockerignore 排除了 wrapper jar），
        所以版本号在两处各写一遍。此前没有任何东西保证它们一致：wrapper 升到 9.4
        而镜像还停在 9.3.1 时，本地与 CI 跑的就不是同一个构建工具，而两边都是绿的。"""
        wrapper = (ROOT / 'backend/gradle/wrapper/gradle-wrapper.properties').read_text()
        version = re.search(r'^distributionUrl=.*gradle-(\d+\.\d+\.\d+)-bin\.zip', wrapper, re.MULTILINE)
        self.assertIsNotNone(version, 'wrapper 里没有可识别的 gradle 版本')
        docker = (ROOT / 'Dockerfile').read_text()
        image = re.search(r'^FROM gradle:(\d+\.\d+\.\d+)-', docker, re.MULTILINE)
        self.assertIsNotNone(image, 'Dockerfile 里没有 gradle 构建阶段')
        self.assertEqual(
            version.group(1), image.group(1),
            f"wrapper 用 gradle {version.group(1)}，镜像用 {image.group(1)}：两处必须同步",
        )

    def test_backend_dependencies_are_cached_before_source_copy(self):
        text = (ROOT / 'Dockerfile').read_text()
        config = text.find('COPY backend/build.gradle.kts')
        warm = text.find('gradle dependencies')
        source = text.find('COPY backend/src/')
        self.assertGreaterEqual(config, 0, '构建配置应与源码分层复制')
        self.assertGreater(warm, config, '依赖缓存必须在构建配置后准备')
        self.assertGreater(source, warm, '源码变化不应失效已下载依赖层')

    def test_dependency_prefetch_resolves_artifacts_not_only_metadata(self):
        build = (ROOT / 'backend/build.gradle.kts').read_text()
        docker = (ROOT / 'Dockerfile').read_text()
        self.assertIn('tasks.register("prefetchDependencies")', build)
        self.assertIn('configurations.getByName(name).files', build)
        self.assertIn('gradle dependencies prefetchDependencies', docker)

    def test_external_build_inputs_are_pinned(self):
        # 扫全部 workflow 而不只是 ci.yml：新增的发布/夜间工作流同样会引入外部 action，
        # 只钉一个文件等于给后来者留了一扇没锁的门。
        workflows = sorted((ROOT / '.github/workflows').glob('*.yml'))
        self.assertTrue(workflows, '.github/workflows 下没有任何 workflow')
        for path in workflows:
            uses = re.findall(r'uses:\s*([^\s#]+)', path.read_text())
            self.assertTrue(uses, f'{path.name} 没有引用任何 action')
            unpinned = [action for action in uses if not re.search(r'@[0-9a-f]{40}$', action)]
            self.assertEqual([], unpinned, f'{path.name} 里有未按 commit SHA 钉死的 action：{unpinned}')
        docker = (ROOT / 'Dockerfile').read_text()
        images = re.findall(r'^FROM\s+(\S+)', docker, re.MULTILINE)
        self.assertTrue(all(re.search(r'@sha256:[0-9a-f]{64}$', image) for image in images))
        self.assertRegex((ROOT / 'e2e/requirements.txt').read_text(), r'^playwright==\d+\.\d+\.\d+\s*$')

    def test_smoke_temp_paths_are_unique_and_honor_tmpdir(self):
        text = (ROOT / 'scripts/live-smoke.sh').read_text()
        self.assertNotRegex(text, r'/tmp/animeko-live-[abce]')
        self.assertRegex(text, r'mktemp\s+-d.*TMPDIR')
        self.assertIn('trap cleanup EXIT', text)
        self.assertLess(text.find('trap cleanup EXIT'), text.find('start "$A"'))

    def test_ci_keeps_failed_backend_test_reports(self):
        text = (ROOT / '.github/workflows/ci.yml').read_text()
        self.assertIn('backend/build/test-results/', text)
        self.assertIn('backend/build/reports/tests/', text)
        self.assertIn('if: failure()', text)


if __name__ == '__main__':
    unittest.main()
