import os
import re
from datetime import datetime

SRC_DIR    = "src/main/java"
FLYWAY_DIR = "src/main/resources/db/migration"
OUTPUT     = "PROJECT_CONTEXT.md"

CLASS_REGEX      = re.compile(r"(?:public|protected)\s+(?:static\s+)?(class|interface|record|enum)\s+(\w+)(?:\s+extends\s+(\w+))?(?:\s+implements\s+([\w\s,<>]+?))?\s*[{(]")
ANNOTATION_REGEX = re.compile(r"@(RestController|Controller|Service|Repository|Component|Configuration|Entity|Table|ConditionalOnProperty|RestControllerAdvice|ControllerAdvice)\b")
ROUTE_REGEX      = re.compile(r'@(Get|Post|Put|Delete|Patch|Request)Mapping\s*\((?:[^)]*?value\s*=\s*)?["\']([^"\']+)["\']')
DEP_REGEX        = re.compile(r"^\s*private\s+final\s+(\w+)\s+\w+\s*[;=]")
VALUE_REGEX      = re.compile(r'@Value\("\$\{([^}:]+)(?::([^}]*))?\}"\)')
COND_REGEX       = re.compile(r'@ConditionalOnProperty\([^)]*?(?:name|value)\s*=\s*"([^"]+)"')
FLYWAY_FILE      = re.compile(r"^V(\d+)__(.+)\.sql$")
METHOD_START     = re.compile(r"^\s+public\s+")
TODO_REGEX       = re.compile(r"//\s*(TODO|FIXME|HACK|XXX)\s*:?\s*(.+?)\s*$")

SKIP_TYPES = {
    "String","Long","Integer","Boolean","UUID","int","long","boolean","void","double","float",
    "Object","List","Map","Optional","CompletableFuture","ResponseEntity","Set",
    "AtomicBoolean","AtomicInteger","AtomicReference",
    "ScheduledExecutorService","ExecutorService","ConcurrentHashMap","ConcurrentMap",
}
SKIP_METHOD_NAMES  = {"toString","equals","hashCode","getBean"}
CLASS_KIND_TOKENS  = {"class","interface","record","enum"}

# -------- file analysis --------

def analyze_java_file(file_path):
    with open(file_path, 'r', encoding='utf-8') as f:
        content = f.read()
    lines = content.split('\n')

    annotations  = []
    routes       = []        # list of (verb, path) for method-level
    base_path    = ""        # class-level @RequestMapping
    dependencies = set()
    methods      = []
    type_names   = set()     # all class/record/etc names in file (to exclude from methods)
    cond_props   = []        # @ConditionalOnProperty names
    values       = []        # (key, default) tuples for @Value
    todos        = []        # (kind, text, line_no)
    class_info   = None      # (kind, name, signature) of the outer class

    for lineno, line in enumerate(lines, 1):
        stripped = line.strip()

        # TODO / FIXME / HACK / XXX
        tm = TODO_REGEX.search(line)
        if tm:
            todos.append((tm.group(1), tm.group(2), lineno))

        # Annotations
        for ann in ANNOTATION_REGEX.finditer(stripped):
            annotations.append(ann.group(1))

        # @ConditionalOnProperty
        for cm in COND_REGEX.finditer(stripped):
            cond_props.append(cm.group(1))

        # @Value
        for vm in VALUE_REGEX.finditer(stripped):
            values.append((vm.group(1), vm.group(2) or ""))

        # Routes: distinguish class-level (before class decl) from method-level
        for rm in ROUTE_REGEX.finditer(stripped):
            verb, path = rm.group(1), rm.group(2)
            if class_info is None and verb == "Request":
                base_path = path
            else:
                routes.append((verb, path))

        # Constructor dependencies
        dm = DEP_REGEX.match(line)
        if dm and dm.group(1) not in SKIP_TYPES:
            dependencies.add(dm.group(1))

        # Class / record / interface / enum
        cm = CLASS_REGEX.search(stripped)
        if cm:
            kind, name = cm.group(1), cm.group(2)
            type_names.add(name)
            if class_info is None:
                ext  = f" extends {cm.group(3)}" if cm.group(3) else ""
                impl = f" implements {cm.group(4).strip()}" if cm.group(4) else ""
                class_info = (kind, name, ext + impl)

        # Method detection: take last word before ( as method name
        if METHOD_START.match(line) and "(" in line:
            before = line.split("(", 1)[0]
            tokens = before.split()
            if len(tokens) >= 2:
                name = tokens[-1]
                # Skip class declarations (public class Foo, public record Bar...)
                if any(t in CLASS_KIND_TOKENS for t in tokens):
                    pass
                elif name in SKIP_METHOD_NAMES:
                    pass
                elif name in type_names:
                    pass  # constructor or inner record name
                else:
                    methods.append(name)

    # Remove method names that turned out to be inner type names discovered later
    methods = [m for m in methods if m not in type_names]
    # Combine class-level base path with method routes
    full_routes = [(v.replace("Request", "ALL").upper(), base_path + p) for v, p in routes]

    return {
        "annotations": annotations,
        "class_info":  class_info,
        "routes":      full_routes,
        "deps":        sorted(dependencies),
        "methods":     methods[:10],
        "cond_props":  cond_props,
        "values":      values,
        "todos":       todos,
    }

def rel(path):
    return path.replace("\\", "/")

# -------- aggregate scans --------

def collect_classes():
    classes = []
    all_values = []
    all_todos  = []  # (path, kind, text, lineno)
    for root, _, files in os.walk(SRC_DIR):
        for f in sorted(files):
            if not f.endswith('.java'):
                continue
            full = os.path.join(root, f)
            info = analyze_java_file(full)
            all_values.extend(info["values"])
            for kind, text, lineno in info["todos"]:
                all_todos.append((rel(full), kind, text, lineno))
            if not info["class_info"]:
                continue
            pkg = os.path.relpath(root, SRC_DIR).replace(os.sep, '.')
            classes.append({
                "pkg":         pkg,
                "kind":        info["class_info"][0],
                "name":        info["class_info"][1],
                "signature":   info["class_info"][2],
                "annotations": info["annotations"],
                "routes":      info["routes"],
                "deps":        info["deps"],
                "methods":     info["methods"],
                "cond_props":  info["cond_props"],
                "path":        rel(full),
            })
    # Deduplicate property keys, keep first non-empty default
    value_map = {}
    for key, default in all_values:
        if key not in value_map or (default and not value_map[key]):
            value_map[key] = default
    return classes, sorted(value_map.items()), all_todos

def collect_migrations():
    out = []
    if os.path.isdir(FLYWAY_DIR):
        for f in sorted(os.listdir(FLYWAY_DIR)):
            m = FLYWAY_FILE.match(f)
            if m:
                version = m.group(1)
                desc = m.group(2).replace("_", " ")
                out.append((version, desc, f))
    return out

# -------- output --------

def write_section(out, title):
    out.write(f"## {title}\n\n")

def generate_context():
    classes, properties, todos = collect_classes()
    migrations                 = collect_migrations()
    controllers = [c for c in classes if any(a in ("RestController","Controller") for a in c["annotations"])]
    services    = [c for c in classes if "Service" in c["annotations"]]

    with open(OUTPUT, 'w', encoding='utf-8') as out:
        out.write("# Project Class Map\n")
        out.write(f"Generated: {datetime.now().strftime('%Y-%m-%d %H:%M')}\n\n")
        out.write("> Read this before searching src/ broadly. Use paths here to navigate directly.\n\n")

        # REST endpoints with combined base + method paths
        if controllers:
            write_section(out, "REST API Endpoints")
            for c in controllers:
                for verb, path in c["routes"]:
                    out.write(f"- `{verb} {path}` -> {c['name']}\n")
            out.write("\n")

        # Service dep graph
        if services:
            write_section(out, "Service Dependency Graph")
            for c in services:
                if c["deps"]:
                    out.write(f"- {c['name']} -> {', '.join(c['deps'])}\n")
            out.write("\n")

        # Flyway migrations
        if migrations:
            write_section(out, "Flyway Migrations")
            for version, desc, fname in migrations:
                out.write(f"- V{version}: {desc} (`{fname}`)\n")
            out.write("\n")

        # @Value properties
        if properties:
            write_section(out, "Configuration Properties (@Value)")
            for key, default in properties:
                default_str = f" *(default: `{default}`)*" if default else ""
                out.write(f"- `{key}`{default_str}\n")
            out.write("\n")

        # Conditionally-loaded services
        cond_classes = [c for c in classes if c["cond_props"]]
        if cond_classes:
            write_section(out, "Conditional Services (@ConditionalOnProperty)")
            for c in cond_classes:
                props = ", ".join(f"`{p}`" for p in c["cond_props"])
                out.write(f"- {c['name']} requires: {props}\n")
            out.write("\n")

        # In-code TODOs (TODO / FIXME / HACK / XXX)
        if todos:
            write_section(out, "In-code TODOs")
            out.write("> Items below live as comments in the source. Promote actionable ones to `BACKLOG.md` and remove the comment.\n\n")
            for path, kind, text, lineno in todos:
                out.write(f"- **{kind}** `{path}:{lineno}` — {text}\n")
            out.write("\n")

        # Full class map
        write_section(out, "Class Map")
        packages = {}
        for c in classes:
            packages.setdefault(c["pkg"], []).append(c)

        for pkg in sorted(packages):
            out.write(f"### {pkg}\n\n")
            for c in packages[pkg]:
                ann = f" [{', '.join(c['annotations'])}]" if c["annotations"] else ""
                out.write(f"**{c['kind']} {c['name']}**{ann}{c['signature']}\n")
                out.write(f"Path: `{c['path']}`\n")
                if c["routes"]:
                    rs = ", ".join(f"`{v} {p}`" for v, p in c["routes"])
                    out.write(f"Routes: {rs}\n")
                if c["deps"]:
                    out.write(f"Deps: {', '.join(c['deps'])}\n")
                if c["methods"]:
                    out.write(f"Methods: {', '.join(f'`{m}()`' for m in c['methods'])}\n")
                out.write("\n")

if __name__ == "__main__":
    if os.path.exists(SRC_DIR):
        generate_context()
        print(f"[OK] {OUTPUT} generated.")
    else:
        print(f"[ERROR] {SRC_DIR} not found")
