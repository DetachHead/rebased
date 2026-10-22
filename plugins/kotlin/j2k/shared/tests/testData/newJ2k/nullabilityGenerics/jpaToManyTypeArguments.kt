import jakarta.persistence.OneToMany

class J {
    @OneToMany
    private var wildcard: MutableList<out CharSequence> = ArrayList<CharSequence>()

    @OneToMany
    private var byName: MutableMap<String, Int> = HashMap<String, Int>()

    @OneToMany
    private var raw: MutableList<*> = ArrayList<Any?>()
}
