import jakarta.persistence.Column
import jakarta.persistence.OneToMany
import javax.persistence.ManyToMany

class J {
    @OneToMany
    private var children: MutableList<String> = ArrayList<String>()

    @ManyToMany
    private var peers: MutableList<String> = ArrayList<String>()

    // Not a to-many relationship: the elements stay nullable
    @Column
    private var tags: MutableList<String?> = ArrayList<String?>()

    // No JPA annotation: the elements stay nullable
    private val plain: MutableList<String?> = ArrayList<String?>()

    @get:OneToMany
    val grandChildren: MutableList<String>
        get() = ArrayList<String>()
}
