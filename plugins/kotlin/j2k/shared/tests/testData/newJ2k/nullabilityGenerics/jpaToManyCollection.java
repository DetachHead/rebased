// ADD_JPA_ANNOTATIONS
// KTIJ-33702: a JPA provider returns an empty collection for a to-many relationship
import jakarta.persistence.Column;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;

import java.util.ArrayList;
import java.util.List;

public class J {
    @OneToMany
    private List<String> children = new ArrayList<>();

    @javax.persistence.ManyToMany
    private List<String> peers = new ArrayList<>();

    // Not a to-many relationship: the elements stay nullable
    @Column
    private List<String> tags = new ArrayList<>();

    // No JPA annotation: the elements stay nullable
    private List<String> plain = new ArrayList<>();

    @OneToMany
    public List<String> getGrandChildren() {
        return new ArrayList<>();
    }
}
