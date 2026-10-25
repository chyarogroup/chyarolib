package hr.chyaro.lib.addon;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class AddonDependencyGraph {
    private AddonDependencyGraph() { }

    static List<String> sort(Map<String, List<String>> dependencies) {
        List<String> output = new ArrayList<>();
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        dependencies.keySet().stream().sorted().forEach(id -> visit(id, dependencies, visiting, visited, output));
        return output;
    }

    private static void visit(String id, Map<String, List<String>> all, Set<String> visiting,
                              Set<String> visited, List<String> output) {
        if (visited.contains(id)) return;
        if (!all.containsKey(id)) throw new IllegalArgumentException("Missing addon dependency " + id);
        if (!visiting.add(id)) throw new IllegalArgumentException("Addon dependency cycle involving " + id);
        for (String dependency : all.get(id)) visit(dependency, all, visiting, visited, output);
        visiting.remove(id);
        visited.add(id);
        output.add(id);
    }
}
