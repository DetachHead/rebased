// ADD_JPA_ANNOTATIONS
// KTIJ-33702: an explicit null still wins over the to-many rule
import jakarta.persistence.OneToMany;

import java.util.ArrayList;
import java.util.List;

public class J {
    @OneToMany
    private List<String> children = new ArrayList<>();

    @OneToMany
    public List<String> getOrphans() {
        return null;
    }

    public void clear() {
        children = null;
    }
}
