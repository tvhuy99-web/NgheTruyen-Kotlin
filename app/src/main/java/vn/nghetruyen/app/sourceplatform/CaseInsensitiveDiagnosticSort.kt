package vn.nghetruyen.app.sourceplatform

/** Comparator overload used by diagnostic-only string collections. */
internal fun Iterable<String>.sorted(comparator: Comparator<in String>): List<String> = sortedWith(comparator)
