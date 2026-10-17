package com.example.myime

/**
 * 按某个 key 给 0 until n 的下标排序,结果和 `indices.sortedBy { key(it) }.toIntArray()` 完全一样(稳定:key 相同的保持原顺序),
 * 但全程用 IntArray,不把每个下标装箱成 Integer 对象:词库有几十万条时,加载后第一次建索引不再产生一大堆垃圾回收。
 */
object IdxSort {
    fun <K : Comparable<K>> by(n: Int, key: (Int) -> K): IntArray {
        var a = IntArray(n) { it }
        if (n < 2) return a
        val ks = ArrayList<K>(n)
        for (i in 0 until n) ks.add(key(i))
        var b = IntArray(n)
        var w = 1
        while (w < n) {
            var i = 0
            while (i < n) {
                val mid = minOf(i + w, n)
                val hi = minOf(i + 2 * w, n)
                var l = i
                var r = mid
                var k = i
                while (l < mid && r < hi) {
                    if (ks[a[r]].compareTo(ks[a[l]]) < 0) b[k++] = a[r++] else b[k++] = a[l++]   // 右边严格更小才先取右边,保证稳定
                }
                while (l < mid) b[k++] = a[l++]
                while (r < hi) b[k++] = a[r++]
                i += 2 * w
            }
            val t = a
            a = b
            b = t
            w *= 2
        }
        return a
    }
}
