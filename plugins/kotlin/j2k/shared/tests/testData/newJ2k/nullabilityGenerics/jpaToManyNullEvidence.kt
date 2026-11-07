import jakarta.persistence.OneToMany

class J {
    @OneToMany
    private var children: MutableList<String>? = ArrayList<String>()

    @get:OneToMany
    val orphans: MutableList<String>?
        get() = null

    fun clear() {
        children = null
    }
}
