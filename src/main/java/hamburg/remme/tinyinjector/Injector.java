package hamburg.remme.tinyinjector;

import static java.util.Spliterator.ORDERED;
import static java.util.Spliterators.spliteratorUnknownSize;
import static java.util.stream.Collectors.toList;
import static java.util.stream.StreamSupport.stream;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Parameter;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

/**
 * TinyInjector aims to be a very light weight dependency injection library. For that reason it is written in Java and
 * has zero dependencies.
 * <p>
 * It only uses constructor dependency injection for that.
 * <p>
 * The class-path is automatically being searched for classes annotated with an annotation of your choice, although they
 * all have to use the same annotation. All matching classes are topologically sorted and instantiated.
 *
 * <h2>Example</h2>
 *
 * <pre>
 * {@literal @}Component public class Foo() {
 *     public void beep() { ... }
 * }
 *
 * {@literal @}Component public class Bar(Foo foo) { ... }
 * </pre>
 * <p>
 * To automatically scan for these classes just call:
 *
 * <pre>
 * import static hamburg.remme.tinyinjector.Injector.scan;
 * import hamburg.remme.tinyinjector.Component;
 *
 * public static final main(String[] args) {
 *     scan(Component.class, "my.package");
 * }
 * </pre>
 * <p>
 * A {@code Map} of singletons is being created as a result.
 * <p>
 * To retrieve a singleton just call:
 *
 * <pre>
 * import static hamburg.remme.tinyinjector.Injector.scan;
 * import hamburg.remme.tinyinjector.Component;
 *
 * public static final main(String[] args) {
 *     scan(Component.class, "my.package");
 *     var foo = retrieve(Foo.class);
 *     foo.beep();
 * }
 * </pre>
 *
 * @author Dennis Remme (dennis@remme.hamburg)
 */
public final class Injector {

    private static final String CLASS_EXTENSION = ".class";
    private static Map<Class<?>, Object> DEPENDENCY_MAP;

    private Injector() {
    }

    /**
     * Scans the class-path for classes annotated with {@link Component}, but only if they are inside the given
     * package.
     * <p>
     * A {@link Map} of singletons is created as a result.
     *
     * @throws IllegalArgumentException when there are cyclic or missing dependencies
     * @throws IllegalStateException    when the class-path has already been scanned
     * @see #retrieve(Class)
     */
    public static void scan(String packageName) {
        scan(Component.class, packageName);
    }

    /**
     * Scans the class-path for classes annotated with the given class, but only if they are inside the given package.
     * <p>
     * A {@link Map} of singletons is created as a result.
     *
     * @throws IllegalArgumentException when there are cyclic or missing dependencies
     * @throws IllegalStateException    when the class-path has already been scanned
     * @see #retrieve(Class)
     */
    public static void scan(Class<? extends Annotation> annotationClass, String packageName) {
        if (DEPENDENCY_MAP != null) throw new IllegalStateException("Class-path has already been scanned.");

        String basePath = packageName.replace('.', '/');
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

        // Scan classes with attached annotation
        Stream<String> classNames = isExecutingJar() ? scanJar(basePath) : scanFiles(basePath, classLoader);
        Stream<? extends Class<?>> classes = classNames.map(it -> substringAfter(it, basePath).replace('/', '.'))
                .map(it -> packageName + "." + it)
                .map(it -> it.substring(0, it.length() - CLASS_EXTENSION.length()))
                .map(it -> asClass(it, classLoader))
                .filter(it -> it.isAnnotationPresent(annotationClass));

        // Create graph nodes
        Set<ClassNode> graph = new HashSet<>();
        classes.forEach(clazz -> getOrCreate(graph, clazz).neighbors
                .addAll(primaryParameters(clazz).stream()
                        .map(it -> getOrCreate(graph, it.getType()))
                        .collect(toList())));
        Map<ClassNode, Integer> indegree = new HashMap<>();
        graph.forEach(it -> indegree.put(it, 0));
        graph.stream().flatMap(it -> it.neighbors.stream()).forEach(it -> indegree.put(it, indegree.get(it) + 1));

        // Topologically sort the nodes
        List<Class<?>> sorted = new ArrayList<>();
        LinkedList<ClassNode> queue = new LinkedList<>();

        graph.stream().filter(it -> indegree.get(it) == 0).forEach(it -> {
            queue.offer(it);
            sorted.add(it.value);
        });

        while (!queue.isEmpty()) {
            queue.poll().neighbors.forEach(it -> {
                indegree.put(it, indegree.get(it) - 1);
                if (indegree.get(it) == 0) {
                    queue.offer(it);
                    sorted.add(0, it.value);
                }
            });
        }

        if (sorted.size() != graph.size()) throw new RuntimeException("Cyclic dependencies detected.");

        // Instantiate
        DEPENDENCY_MAP = new HashMap<>();
        sorted.forEach(clazz -> {
            try {
                Object[] arguments = primaryParameters(clazz).stream().map(it -> DEPENDENCY_MAP.get(it.getType())).toArray();
                DEPENDENCY_MAP.put(clazz, primaryConstructor(clazz).newInstance(arguments));
            } catch (NullPointerException e) {
                throw new IllegalArgumentException("Missing dependency.", e);
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
                throw new IllegalArgumentException("Injecting dependecies failed for class " + clazz);
            }
        });
    }

    private static Stream<String> scanJar(String basePath) {
        return asStream(getJar().entries())
                .map(ZipEntry::getName)
                .filter(it -> it.startsWith(basePath))
                .filter(it -> it.toLowerCase().endsWith(CLASS_EXTENSION));
    }

    private static Stream<String> scanFiles(String basePath, ClassLoader classLoader) {
        try {
            return asStream(classLoader.getResources(basePath))
                    .map(URL::getFile)
                    .map(Paths::get)
                    .flatMap(Injector::walk)
                    .map(Path::toString)
                    .filter(it -> it.toLowerCase().endsWith(CLASS_EXTENSION));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static ClassNode getOrCreate(Set<ClassNode> graph, Class<?> clazz) {
        return graph.stream().filter(it -> it.value.equals(clazz)).findAny().orElseGet(() -> {
            ClassNode node = new ClassNode(clazz);
            graph.add(node);
            return node;
        });
    }

    private static Constructor primaryConstructor(Class<?> clazz) {
        return clazz.getDeclaredConstructors()[0];
    }

    private static List<Parameter> primaryParameters(Class<?> clazz) {
        return Arrays.asList(primaryConstructor(clazz).getParameters());
    }

    /**
     * Retrieves a singleton instance of the class.
     *
     * @throws IllegalStateException    when no classes have been scanned yet
     * @throws IllegalArgumentException when the class has not been scanned
     */
    @SuppressWarnings("unchecked") public static <T> T retrieve(Class<T> clazz) {
        if (DEPENDENCY_MAP == null) throw new IllegalStateException("Retrieve called before scanning class-path.");
        if (!DEPENDENCY_MAP.containsKey(clazz)) throw new IllegalArgumentException("No such singleton.");
        return (T) DEPENDENCY_MAP.get(clazz);
    }

    private static boolean isExecutingJar() {
        return Injector.class.getResource(Injector.class.getSimpleName() + ".class").toExternalForm().startsWith("jar");
    }

    private static JarFile getJar() {
        try {
            return new JarFile(new File(Injector.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
        } catch (IOException | URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    private static Stream<Path> walk(Path path) {
        try {
            return Files.walk(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String substringAfter(String source, String delimiter) {
        return source.substring(source.lastIndexOf(delimiter) + delimiter.length() + 1);
    }

    private static <T> Stream<T> asStream(Enumeration<T> enumeration) {
        return stream(spliteratorUnknownSize(enumeration.asIterator(), ORDERED), false);
    }

    private static Class<?> asClass(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Used for topological sort.
     */
    static class ClassNode {

        Class<?> value;
        List<ClassNode> neighbors = new ArrayList<>();

        ClassNode(Class<?> value) {
            this.value = value;
        }

    }

}
