package com.animeko.web.selector

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Small JsonPath subset: properties, wildcards, array indices and recursive descent. */
object JsonPathEval {
    private const val MAX_NODES = 20_000
    private const val MAX_WORK = 200_000
    internal const val MAX_PATH_LENGTH = 2_048
    private const val MAX_STEPS = 64
    private const val MAX_DEPTH = 128
    private const val MAX_PLANS = 256

    private class BudgetExceeded : RuntimeException(null, null, false, false)

    /** Per evaluation/request, never shared with a compiled plan or another caller. */
    class Budget(
        private val maxWork: Int = MAX_WORK,
        internal val maxResults: Int = MAX_NODES,
        private val checkCancelled: () -> Unit = {},
    ) {
        var workUsed: Int = 0
            private set

        internal fun visit() {
            if (workUsed % 128 == 0) checkCancelled()
            if (++workUsed > maxWork) throw BudgetExceeded()
        }
    }

    private sealed interface Step {
        data class Property(val keys: List<String>) : Step
        data object Wildcard : Step
        data class Index(val value: Int) : Step
        data class Deep(val key: String) : Step
    }

    private data class Plan(val steps: List<Step>)
    private val plans = object : LinkedHashMap<String, Plan?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Plan?>): Boolean = size > MAX_PLANS
    }

    fun evaluate(path: String, root: JsonElement, budget: Budget = Budget()): List<JsonElement>? = bounded {
        val plan = compile(path) ?: return@bounded null
        execute(plan.steps, listOf(root), aligned = false, budget)
    }

    /** Missing properties (including intermediate objects) keep an explicit position. */
    fun evaluateAligned(path: String, root: JsonElement, budget: Budget = Budget()): List<JsonElement>? = bounded {
        val plan = compile(path) ?: return@bounded null
        execute(plan.steps, listOf(root), aligned = true, budget)
    }

    internal data class IndexedRecord(val record: JsonElement, val name: JsonElement?, val link: JsonElement?)

    /** Resolve both fields against the same records; unrelated fan-outs are not safe to zip. */
    internal fun indexedValues(
        namePath: String,
        linkPath: String,
        root: JsonElement,
        budget: Budget = Budget(),
    ): List<IndexedRecord>? = bounded {
        val names = compile(namePath)?.steps ?: return@bounded null
        val links = compile(linkPath)?.steps ?: return@bounded null
        var common = 0
        while (common < minOf(names.size, links.size) && names[common] == links[common]) common++
        // Identical field selectors still need their parent records, not the field values.
        if (common == names.size && common == links.size && common > 0) common--
        val nameTail = names.drop(common)
        val linkTail = links.drop(common)
        if ((nameTail + linkTail).any { it is Step.Wildcard || it is Step.Deep }) return@bounded null
        val context = execute(names.take(common), listOf(root), aligned = false, budget)
        val records = Collector(budget)
        for (element in context) walkArrayLeaves(element, budget) { records.emit(it) }
        val result = ArrayList<IndexedRecord>(records.values.size)
        for (record in records.values) {
            val name = execute(nameTail, listOf(record), aligned = true, budget).firstOrNull()
            val link = execute(linkTail, listOf(record), aligned = true, budget).firstOrNull()
            result.add(IndexedRecord(record, name, link))
        }
        result
    }

    private inline fun <T> bounded(block: () -> T): T? = try {
        block()
    } catch (_: BudgetExceeded) {
        null
    }

    private class Collector(private val budget: Budget) {
        val values = ArrayList<JsonElement>()
        fun emit(value: JsonElement) {
            budget.visit()
            if (values.size >= budget.maxResults) throw BudgetExceeded()
            values.add(value)
        }
    }

    private fun execute(steps: List<Step>, initial: List<JsonElement>, aligned: Boolean, budget: Budget): List<JsonElement> {
        var current = initial
        for (step in steps) {
            val out = Collector(budget)
            for (element in current) {
                when (step) {
                    is Step.Property -> {
                        if (aligned) {
                            if (step.keys.isNotEmpty()) walkArrayLeaves(element, budget) { leaf ->
                                val obj = leaf as? JsonObject
                                var value: JsonElement = JsonNull
                                for (key in step.keys) {
                                    budget.visit()
                                    val found = obj?.get(key)
                                    if (found != null) { value = found; break }
                                }
                                out.emit(value)
                            }
                        } else {
                            // Preserve the general evaluator's key-major multi-property order.
                            for (key in step.keys) walkArrayLeaves(element, budget) { leaf ->
                                budget.visit()
                                (leaf as? JsonObject)?.get(key)?.let(out::emit)
                            }
                        }
                    }
                    Step.Wildcard -> {
                        budget.visit()
                        val children = when (element) {
                            is JsonArray -> element
                            is JsonObject -> element.values
                            else -> emptyList()
                        }
                        for (child in children) out.emit(child)
                    }
                    is Step.Index -> {
                        budget.visit()
                        val value = (element as? JsonArray)?.getOrNull(step.value)
                        if (value != null) out.emit(value) else if (aligned) out.emit(JsonNull)
                    }
                    is Step.Deep -> deepScan(element, step.key, out, budget)
                }
            }
            current = out.values
        }
        return current
    }

    /** Iterator frames avoid both recursive stack growth and eagerly pushing wide arrays. */
    private fun walkArrayLeaves(root: JsonElement, budget: Budget, leaf: (JsonElement) -> Unit) {
        val stack = ArrayDeque<Iterator<JsonElement>>()
        var items = listOf(root).iterator()
        while (true) {
            if (items.hasNext()) {
                val element = items.next()
                budget.visit()
                if (element is JsonArray) {
                    if (stack.size >= MAX_DEPTH) throw BudgetExceeded()
                    stack.addLast(items)
                    items = element.iterator()
                } else leaf(element)
            } else {
                if (stack.isEmpty()) return
                items = stack.removeLast()
            }
        }
    }

    private fun deepScan(root: JsonElement, key: String, out: Collector, budget: Budget) {
        val stack = ArrayDeque<Iterator<JsonElement>>()
        var items = listOf(root).iterator()
        while (true) {
            if (items.hasNext()) {
                val element = items.next()
                budget.visit()
                val children = when (element) {
                    is JsonObject -> {
                        element[key]?.let(out::emit)
                        element.values
                    }
                    is JsonArray -> element
                    else -> null
                }
                if (children != null) {
                    if (stack.size >= MAX_DEPTH) throw BudgetExceeded()
                    stack.addLast(items)
                    items = children.iterator()
                }
            } else {
                if (stack.isEmpty()) return
                items = stack.removeLast()
            }
        }
    }

    private fun compile(path: String): Plan? {
        if (path.length > MAX_PATH_LENGTH) return null
        val p = path.trim()
        return synchronized(plans) {
            if (plans.containsKey(p)) plans[p] else parse(p).also { plans[p] = it }
        }
    }

    private fun parse(p: String): Plan? {
        if (!p.startsWith('$')) return null
        val steps = ArrayList<Step>()
        var i = 1
        while (i < p.length) {
            if (steps.size >= MAX_STEPS) return null
            when (p[i]) {
                '.' -> {
                    i++
                    val deep = p.getOrNull(i) == '.'
                    if (deep) i++
                    if (!deep && p.getOrNull(i) == '*') {
                        steps.add(Step.Wildcard)
                        i++
                    } else {
                        val start = i
                        while (i < p.length && (p[i].isLetterOrDigit() || p[i] in "_-")) i++
                        if (start == i) return null
                        val key = p.substring(start, i)
                        steps.add(if (deep) Step.Deep(key) else Step.Property(listOf(key)))
                    }
                }
                '[' -> {
                    val start = ++i
                    var quote: Char? = null
                    while (i < p.length) {
                        val ch = p[i]
                        if (quote != null) {
                            if (ch == '\\') i++ else if (ch == quote) quote = null
                        } else {
                            if (ch == '\'' || ch == '"') quote = ch else if (ch == ']') break
                            else if (ch == '[') return null
                        }
                        i++
                    }
                    if (i >= p.length || quote != null) return null
                    val inner = p.substring(start, i).trim()
                    val step = when {
                        inner == "*" -> Step.Wildcard
                        inner.toIntOrNull() != null -> Step.Index(inner.toInt())
                        inner.startsWith('\'') || inner.startsWith('"') -> Step.Property(readQuotedKeys(inner) ?: return null)
                        inner.isEmpty() -> Step.Property(emptyList())
                        inner.all { it.isLetterOrDigit() || it in "_-" } -> Step.Property(listOf(inner))
                        else -> return null
                    }
                    steps.add(step)
                    i++
                }
                else -> return null
            }
        }
        return Plan(steps)
    }

    private fun readQuotedKeys(inner: String): List<String>? {
        val keys = ArrayList<String>()
        var i = 0
        while (i < inner.length) {
            while (i < inner.length && inner[i].isWhitespace()) i++
            val quote = inner.getOrNull(i++) ?: return null
            if (quote != '\'' && quote != '"') return null
            val key = StringBuilder()
            while (i < inner.length && inner[i] != quote) {
                if (inner[i] == '\\') { i++; if (i == inner.length) return null }
                key.append(inner[i++])
            }
            if (i >= inner.length || key.isEmpty() || keys.size >= 16) return null
            i++
            keys.add(key.toString())
            while (i < inner.length && inner[i].isWhitespace()) i++
            if (i == inner.length) return keys
            if (inner[i++] != ',' || i == inner.length) return null
        }
        return null
    }

    /** Unwrap one value, preserving JSON null instead of the literal string "null". */
    fun singleString(e: JsonElement?): String? {
        var value = e
        repeat(MAX_DEPTH + 1) {
            value = when (val current = value) {
                is JsonArray -> current.firstOrNull()
                is JsonObject -> current.values.firstOrNull()
                is JsonPrimitive -> return current.contentOrNull
                else -> return null
            }
        }
        return null
    }
}
