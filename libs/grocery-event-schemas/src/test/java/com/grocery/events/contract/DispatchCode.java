package com.grocery.events.contract;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.constantpool.StringEntry;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The string constants of a listener's dispatch code, read from its class files (JDK ClassFile API): every
 * {@code case "OrderCreated" ->}, {@code "PriceChanged".equals(...)}, {@code static final String X = "StockReserved"}
 * (inlined where used), {@code Set.of(...)} / {@code Map.of(...)} entry and prefix/suffix piece
 * ({@code startsWith("Vendor")} + {@code case "Approved"}) ends up in the constant pool as a string entry. Compiled
 * code, not source text: comments, javadoc and log messages split around arguments do not count, and a constant
 * declared elsewhere is seen where it is used.
 */
final class DispatchCode {

    /** Parts of an {@code invokedynamic} string-concatenation recipe are separated by these markers. */
    private static final String CONCAT_MARKERS = "[\u0001\u0002]";

    private DispatchCode() {
    }

    /**
     * The string constants of {@code classes} (each with the classes nested in it: member, local and anonymous
     * classes, records, enums) and of every class in {@code packages} (with sub-packages) that lives in the same
     * classpath entry as {@code owner} - the service's main classes, never its test classes of the same package.
     */
    static Set<String> stringConstants(Collection<Class<?>> classes, Collection<String> packages, Class<?> owner) {
        ClassLoader loader = owner.getClassLoader();
        Set<String> constants = new TreeSet<>();
        Set<String> classNames = new LinkedHashSet<>();
        for (Class<?> type : classes) {
            classNames.add(type.getName());
            for (Class<?> member : type.getNestHost().getNestMembers()) {
                if (member.getName().startsWith(type.getName() + "$")) {
                    classNames.add(member.getName());
                }
            }
        }
        for (String className : classNames) {
            String resource = className.replace('.', '/') + ".class";
            try (InputStream in = loader.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalStateException("Class file " + resource + " not found");
                }
                addConstants(in.readAllBytes(), constants);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        for (String packageName : packages) {
            if (!scanPackage(packageName, owner, constants)) {
                throw new IllegalStateException("Package " + packageName + " not found next to " + owner.getName());
            }
        }
        return constants;
    }

    static void addConstants(byte[] classFile, Set<String> constants) {
        ClassModel model = ClassFile.of().parse(classFile);
        for (PoolEntry entry : model.constantPool()) {
            if (entry instanceof StringEntry string) {
                String value = string.stringValue();
                constants.add(value);
                for (String part : value.split(CONCAT_MARKERS)) {
                    if (!part.isEmpty()) {
                        constants.add(part);
                    }
                }
            }
        }
    }

    /** Scans the package in the classpath entry (directory or jar) the owner class was loaded from. */
    private static boolean scanPackage(String packageName, Class<?> owner, Set<String> constants) {
        String path = packageName.replace('.', '/');
        try {
            URL ownerClass = owner.getResource("/" + owner.getName().replace('.', '/') + ".class");
            if (ownerClass == null) {
                throw new IllegalStateException("Class file of " + owner.getName() + " not found");
            }
            URI uri = ownerClass.toURI();
            if ("file".equals(uri.getScheme())) {
                Path root = Path.of(uri);
                for (int i = 0; i < owner.getName().split("\\.").length; i++) {
                    root = root.getParent();                       // up from <root>/com/grocery/.../Owner.class
                }
                Path directory = root.resolve(path);
                if (!Files.isDirectory(directory)) {
                    return false;
                }
                addClassFiles(directory, constants);
                return true;
            }
            if ("jar".equals(uri.getScheme())) {
                String jar = uri.toString().substring(0, uri.toString().indexOf("!/") + 2);
                URI packageUri = URI.create(jar + path);
                FileSystem fileSystem;
                boolean created = false;
                try {
                    fileSystem = FileSystems.newFileSystem(URI.create(jar), Map.of());
                    created = true;
                } catch (FileSystemAlreadyExistsException e) {
                    fileSystem = FileSystems.getFileSystem(URI.create(jar));
                }
                try {
                    Path directory = fileSystem.provider().getPath(packageUri);
                    if (!Files.isDirectory(directory)) {
                        return false;
                    }
                    addClassFiles(directory, constants);
                    return true;
                } finally {
                    if (created) {
                        fileSystem.close();
                    }
                }
            }
            throw new IllegalStateException("Cannot scan " + uri);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void addClassFiles(Path directory, Set<String> constants) throws IOException {
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".class"))::iterator) {
                addConstants(Files.readAllBytes(file), constants);
            }
        }
    }
}
