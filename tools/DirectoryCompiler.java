import java.nio.file.Path;
import com.sun.tools.javac.file.FSInfo;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.api.JavacTool;
import com.sun.tools.javac.file.JavacFileManager;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.*;
import javax.tools.*;
import java.io.*;

/** Verification fallback for a workspace whose Windows ACL denies GetFinalPathNameByHandle. */
public final class DirectoryCompiler {
    public static void main(String[] args) throws Exception {
        Context context = new Context();
        context.put(FSInfo.class, new FSInfo() {
            @Override public Path getCanonicalFile(Path path) { return path.toAbsolutePath().normalize(); }
        });
        var options = new ArrayList<String>();
        var sources = new ArrayList<Path>();
        for (String line : Files.readAllLines(Path.of(args[0].substring(1)))) {
            if (line.startsWith("\"")) line = line.substring(1, line.length() - 1);
            if (line.endsWith(".java")) sources.add(Path.of(line)); else options.add(line);
        }
        Map<String, List<Binary>> packages = new HashMap<>();
        Map<String, Binary> binaries = new HashMap<>();
        String cp = options.get(options.indexOf("-classpath") + 1);
        for (String dir : cp.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            Path root = Path.of(dir);
            try (var paths = Files.walk(root)) {
                for (Path p : paths.filter(p -> p.toString().endsWith(".class")).toList()) {
                    String name = root.relativize(p).toString().replace('\\', '.').replace('/', '.').replaceAll("\\.class$", "");
                    if (name.startsWith("META-INF.") || binaries.containsKey(name)) continue;
                    Binary b = new Binary(p, name); binaries.put(name, b);
                    String pkg = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : "";
                    packages.computeIfAbsent(pkg, k -> new ArrayList<>()).add(b);
                }
            }
        }
        try (var files = new JavacFileManager(context, true, StandardCharsets.UTF_8)) {
            JavaFileManager indexed = new ForwardingJavaFileManager<JavaFileManager>(files) {
                @Override public Iterable<JavaFileObject> list(Location l, String pkg, Set<JavaFileObject.Kind> kinds, boolean recurse) throws IOException {
                    if (l != StandardLocation.CLASS_PATH) return super.list(l, pkg, kinds, recurse);
                    List<JavaFileObject> result = new ArrayList<>();
                    if (kinds.contains(JavaFileObject.Kind.CLASS)) for (var e : packages.entrySet()) if (e.getKey().equals(pkg) || recurse && e.getKey().startsWith(pkg + ".")) result.addAll(e.getValue());
                    return result;
                }
                @Override public String inferBinaryName(Location l, JavaFileObject f) { return f instanceof Binary b ? b.name : super.inferBinaryName(l, f); }
                @Override public JavaFileObject getJavaFileForInput(Location l, String name, JavaFileObject.Kind kind) throws IOException {
                    return l == StandardLocation.CLASS_PATH && kind == JavaFileObject.Kind.CLASS ? binaries.get(name) : super.getJavaFileForInput(l, name, kind);
                }
            };
            context.put(JavaFileManager.class, (JavaFileManager) null);
            boolean ok = JavacTool.create().getTask(null, indexed, null, options, null,
                files.getJavaFileObjectsFromPaths(sources), context).call();
            System.exit(ok ? 0 : 1);
        }
    }
    private static final class Binary extends SimpleJavaFileObject {
        final Path path; final String name;
        Binary(Path path, String name) { super(path.toUri(), Kind.CLASS); this.path = path; this.name = name; }
        @Override public InputStream openInputStream() throws IOException { return Files.newInputStream(path); }
    }
}
