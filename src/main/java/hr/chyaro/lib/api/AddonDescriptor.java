package hr.chyaro.lib.api;

import java.util.List;

public record AddonDescriptor(
        String id,
        String name,
        String version,
        String mainClass,
        String host,
        String hostVersion,
        String chyarolibVersion,
        int apiVersion,
        List<String> dependencies
) {
    public AddonDescriptor {
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
    }
}
