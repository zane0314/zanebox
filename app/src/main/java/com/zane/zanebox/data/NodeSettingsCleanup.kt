package com.zane.zanebox.data

/** Only prune recognized keys whose referenced node/selector is certainly gone. */
fun AppData.withoutStaleNodeSettings():AppData {
    val nodeIds=nodes.map { it.id }.toSet()
    val selectors=groups.map { "group-${it.id}" }.toSet()+merges.map { "merge-${it.id}" }
    val selectorTag=Regex("(?:group|merge)-[0-9]+")
    val nodeTag=Regex("node-([0-9]+)")
    val sortKey=Regex("sort_ping_([0-9]+)")
    val staleSelections=settings.filter { (key,value) ->
        val tag=key.removePrefix("groupSelection.")
        val node=nodeTag.matchEntire(value)
        key.startsWith("groupSelection.") && selectorTag.matches(tag) && (tag !in selectors || (node!=null && node.groupValues[1].toLongOrNull() !in nodeIds))
    }.keys.map { it.removePrefix("groupSelection.") }.toSet()
    return copy(settings=settings.filterKeys { key ->
        val sort=sortKey.matchEntire(key)
        val tag=key.substringAfter('.',"")
        val isSelection=key.startsWith("groupSelection.") || key.startsWith("groupSelectionDelay.")
        val staleSelection=isSelection && (tag in staleSelections || (selectorTag.matches(tag) && tag !in selectors))
        (sort==null || sort.groupValues[1].toLongOrNull() in nodeIds) && !staleSelection
    })
}
