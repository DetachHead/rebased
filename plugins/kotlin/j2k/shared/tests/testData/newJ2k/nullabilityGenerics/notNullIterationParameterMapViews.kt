internal class Totals {
    private val totals: MutableMap<String?, Int> = HashMap<String?, Int>()

    fun put(key: String, amount: Int) {
        totals.put(key, amount)
    }

    fun sum(): Int {
        var sum = 0
        for (value in totals.values) {
            sum += value
        }
        return sum
    }

    fun length(): Int {
        var length = 0
        for (entry in totals.entries) {
            val name: String = entry.key!!
            length += name.length
        }
        return length
    }
}
