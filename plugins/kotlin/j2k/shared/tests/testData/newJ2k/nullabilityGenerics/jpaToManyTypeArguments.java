// ADD_JPA_ANNOTATIONS
// KTIJ-33702: the to-many rule covers every type argument of the collection
import jakarta.persistence.OneToMany;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class J {
    @OneToMany
    private List<? extends CharSequence> wildcard = new java.util.ArrayList<>();

    @OneToMany
    private Map<String, Integer> byName = new HashMap<>();

    @OneToMany
    private List raw = new java.util.ArrayList();
}
