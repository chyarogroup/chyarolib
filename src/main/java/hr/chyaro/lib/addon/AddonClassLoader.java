package hr.chyaro.lib.addon;

import java.net.URL;
import java.net.URLClassLoader;

final class AddonClassLoader extends URLClassLoader {
    static { registerAsParallelCapable(); }

    AddonClassLoader(URL jar, ClassLoader chyarolibLoader) {
        super(new URL[]{jar}, new ApiOnlyParent(chyarolibLoader));
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.startsWith("com.github.gensprout.")) {
            throw new ClassNotFoundException("GenSprout implementation classes are not part of the addon API: " + name);
        }
        if (name.startsWith("hr.chyaro.lib.") && !name.startsWith("hr.chyaro.lib.api.")) {
            throw new ClassNotFoundException("chyarolib internals are not part of the addon API: " + name);
        }
        return super.loadClass(name, resolve);
    }

    private static final class ApiOnlyParent extends ClassLoader {
        private final ClassLoader delegate;

        private ApiOnlyParent(ClassLoader delegate) {
            super(ClassLoader.getPlatformClassLoader());
            this.delegate = delegate;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("hr.chyaro.lib.") && !name.startsWith("hr.chyaro.lib.api.")) {
                throw new ClassNotFoundException("chyarolib internals are not part of the addon API: " + name);
            }
            if (name.startsWith("hr.chyaro.lib.api.") || name.startsWith("org.bukkit.")
                    || name.startsWith("io.papermc.paper.") || name.startsWith("net.kyori.")
                    || name.startsWith("org.jetbrains.")) {
                return delegate.loadClass(name);
            }
            return super.loadClass(name, resolve);
        }
    }
}
