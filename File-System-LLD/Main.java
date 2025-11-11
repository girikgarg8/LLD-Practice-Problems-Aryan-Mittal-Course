import java.util.*;

// CHANGE: sealed interface (requires Java 17); improves type-safety and exhaustiveness
sealed interface FileSystemNode permits DirectoryNode, FileNode{
    public boolean isFile();
};

// CHANGE: Encapsulate children, allow minimal helpers (don't expose the internal field Map directly to client, instead expose helpers to them)
final class DirectoryNode implements FileSystemNode {
    // assuming this will be executed in a single threaded environment
    private final Map <String, FileSystemNode> children = new HashMap<>();

    @Override
    public boolean isFile() {
        return false;
    }

    // CHANGE: helper to get child by name
    public FileSystemNode getChild(String name) {
        return children.get(name);
    }

    // CHANGE: helper to add child if absent (directory)
    public DirectoryNode ensureDirectoryChild(String name) {
        return (DirectoryNode) children.computeIfAbsent(name, k-> new DirectoryNode());
    }

    /*
    Intentional asymmetry:
        ensureDirChild returns DirectoryNode so callers can immediately keep traversing deeper without an extra lookup. It’s used in path-walking loops where you need the returned directory to proceed.
        putFileIfAbsent returns boolean because after creating a file you typically don’t traverse into it; callers only need to know whether a new file was created.
     */

    // CHANGE: Helper to put a file if absent
    public boolean putFileIfAbsent(String name) {
        return children.putIfAbsent(name, new FileNode()) == null;
    }

    // CHANGE: Helper to put a directory if absent
    public boolean putDirectoryIfAbsent(String name) {
        return children.putIfAbsent(name, new DirectoryNode()) == null;
    }

    // CHANGE: Helper to remove child from map, return the removed node for value checks
    public FileSystemNode remove(String name) {
        return children.remove(name);
    }

    // CHANGE: Read-only view of the map for immutability
    public Map <String, FileSystemNode> getChildren() {
        return Collections.unmodifiableMap(children);
    }

    // CHANGE: helper to check emptiness (used by "delete only if directory empty")
    public boolean isEmpty() {
        return children.isEmpty();
    }
};

final class FileNode implements FileSystemNode {

    @Override
    public boolean isFile() {
        return true;
    }

};

// CHANGE: make root final, add path utilities, return booleans/exceptions instead of println
class FileSystemManager {
    private final DirectoryNode root = new DirectoryNode();
    private static final char SLASH_DELIMITER = '/';

    // CHANGE: Add common util function to normalize and validate the input path, skip empty segments (like /a//b) and handle multiple/trailing slashes
    private List<String> normalizeAndValidatePath(String path) {
        if (path == null || path.isEmpty() || path.charAt(0) != SLASH_DELIMITER) {
            throw new IllegalArgumentException("Path must begin with /");
        }
        // In case of empty segments like in the case of /a//b, split will return ["", "a", "", "b"] , we want to skip these empty segments
        String [] raw = path.split("/");
        List <String> parts = new ArrayList<>(raw.length);
        for (int i=1; i<raw.length; i++) { // start iteration from i=1 to skip the first empty string
            if (!raw[i].isEmpty()) parts.add(raw[i]);
        }
        return parts;
    }

//    private boolean validatePath(String path) {
//        if (path == null || path.isEmpty() || path.charAt(0) != SLASH_DELIMITER) return false;
//        return true;
//    }

    /*
        CHANGE: If we carefully analyze the three operations : delete, create and search
        We will notice that the algorithm for all these three operations involves traversing all the way from the parent to the node which matches the last element in the path
        Eg: for an operation on the path '/a/b/c', we will traverse all the way from root to the node which matches the part c.

        Hence, to avoid code duplication, we can have a common method to do this traversal operation.
     */

    private static final class Traversal {
        final DirectoryNode parentNode;
        final String lastPart;

        public Traversal(DirectoryNode parentNode, String lastPart) {
            this.parentNode = parentNode;
            this.lastPart = lastPart;
        }
    };

    // CHANGE: Walk to the parent of the last part, return both the parent node as well as the last part
    private Traversal traversalToLastPart(List <String> parts, boolean createNodes) {
        DirectoryNode current = root;
        if (parts.isEmpty()) {
            return new Traversal(current, ""); // root context
        }
        for (int i=0; i < parts.size()-1; i++) {
            String segment = parts.get(i);
            FileSystemNode next = current.getChild(segment);
            if (next == null) {
                if (!createNodes) {
                    throw new NoSuchElementException("Part does not exist: "+ segment);
                }
                current = current.ensureDirectoryChild(segment);
            }
            else if (next.isFile()) {
                throw new IllegalStateException("Encountered file where directory expected at: "+ segment);
            }
            else {
                current = (DirectoryNode) next;
            }
        }
        return new Traversal(current, parts.get(parts.size()-1));
    }

//    private void create(DirectoryNode currentNode, String [] parts, int currentIndex, int n, boolean isFile) {
//        if (currentIndex >= n) return ;
//
//        Map <String, FileSystemNode> children = currentNode.getChildren();
//        String childPart = parts[currentIndex];
//        // leaf-node
//        if (currentIndex == n-1) {
//            if (isFile) {
//                // TODO: for duplicate file creations at the same path, we don't replace the node. This can be extended later to overwrite if required
//                // Think about passing a flag called 'overwrite' in this method
//                children.putIfAbsent(childPart, new FileNode());
//            }
//            else children.putIfAbsent(childPart, new DirectoryNode());
//            return ;
//        }
//        // non-leaf node
//        // TODO: Again, same concept: if a directory path already exists, we don't overwrite it to avoid loss of data
//        children.putIfAbsent(childPart, new DirectoryNode());
//        FileSystemNode childNode = children.get(childPart);
//        if (!childNode.isFile()) create((DirectoryNode) childNode, parts, currentIndex+1, n, isFile);
//    }
//
//    private boolean search(DirectoryNode currentNode, String [] parts, int currentIndex, int n, boolean isFile) {
//        if (currentIndex >= n) return false;
//        Map <String, FileSystemNode> children = currentNode.getChildren();
//
//        String childPart = parts[currentIndex];
//        if (!children.containsKey(childPart)) return false;
//
//        FileSystemNode childNode = children.get(childPart);
//
//        // leaf-node
//        if (currentIndex == n-1) {
//            return childNode.isFile() == isFile;
//        }
//
//        if (!childNode.isFile()) return search((DirectoryNode) childNode, parts, currentIndex+1, n, isFile);
//        return false;
//    }
//
//    // TODO: Think of returning boolean to indicate if a new file/directory was created, else return false
//    public void create(String path, boolean isFile) {
//        if (!validatePath(path)) {
//            System.out.println("Invalid path detected for creation");
//            return ;
//        }
//        String [] parts = path.split("/");
//        int n = parts.length;
//        if (n==1) {
//            System.out.println("Root directory at / exists already");
//            return ;
//        }
//        create(root, parts, 1, n, isFile);
//    }
//
//    public boolean search(String path, boolean isFile) {
//        if (!validatePath(path)) {
//            System.out.println("Invalid path detected for searching");
//            return false;
//        }
//        String [] parts = path.split("/");
//        int n = parts.length;
//        if (n==1) {
//            System.out.println("Root directory at / found");
//            return true;
//        }
//        return search(root, parts, 1, n, isFile);
//    }
//
//    private void delete(DirectoryNode currentNode, String [] parts, int currentIndex, int n) {
//        if (currentIndex >= n) return ;
//        Map <String, FileSystemNode> children = currentNode.getChildren();
//        String childPart = parts[currentIndex];
//        if (!children.containsKey(childPart)) return ;
//
//        // de-link the leaf node from its parent
//        if (currentIndex == n-1) {
//            children.remove(childPart);
//            return ;
//        }
//
//        FileSystemNode childNode = children.get(childPart);
//        if (!childNode.isFile()) delete((DirectoryNode) childNode, parts, currentIndex+1, n);
//    }
//
//    // TODO: Think of returning boolean to indicate if the file/directory was deleted, else return false
//    public void delete(String path) {
//        if (!validatePath(path)) {
//            System.out.println("Invalid path detected for deletion");
//            return ;
//        }
//        // TODO: Write util to split input string and return, reduce code duplication
//        String [] parts = path.split("/");
//        int n = parts.length;
//        if (n==1) {
//            System.out.println("Delete operation not permitted on root");
//            return ;
//        }
//        // The delete method doesn't differentiate between directories and files. If an explicit confirmation is required for deleting directorries, the method can be overrided
//        // To delete the file/directory at some path, we are just going to delete the linkage of the node with its parent. The rest of the subtree is anyways inaccessible and will be garbage collected
//        delete(root, parts, 1, n);
//    }

    // CHANGE: return true if new directory/path is created, false if it exists already
    public boolean create(String path, boolean isFile, boolean createNewNodes) {
        List <String> parts = normalizeAndValidatePath(path);
        if (parts.isEmpty()) return false; // "/" already exists
        Traversal traversal = traversalToLastPart(parts, createNewNodes);
        DirectoryNode parent = traversal.parentNode;
        String lastPart = traversal.lastPart;

        FileSystemNode existing = parent.getChild(lastPart);
        if (existing != null) {
            return existing.isFile() == isFile; // if the node already exists, also need to check if it is of the same type (directory/file)
        }
        return isFile ? parent.putFileIfAbsent(lastPart) : parent.putDirectoryIfAbsent(lastPart);
    }

    // return true is a file/directory at the specified path was deleted, false otherwise
    public boolean delete(String path, boolean requireEmptyDirectory) {
        List <String> parts = normalizeAndValidatePath(path);
        if (parts.isEmpty()) return false; // cannot delete root
        Traversal traversal = traversalToLastPart(parts, false);
        DirectoryNode parent = traversal.parentNode;
        String lastPart = traversal.lastPart;

        FileSystemNode target = parent.getChild(lastPart);
        if (target == null) return false;
        if (!target.isFile() && requireEmptyDirectory) {
            DirectoryNode dir = (DirectoryNode) target;
            if (!dir.isEmpty()) return false; // "requireEmptyDirectory" is true but the given directory is not empty. Hence, unsuccessful deletion
        }
        return parent.remove(lastPart) != null;
    }

    public boolean search(String path, boolean isFile) {
        List<String> parts = normalizeAndValidatePath(path);
        if (parts.isEmpty()) return !isFile; // "/" is a directory
        Traversal traversal = traversalToLastPart(parts, false);

        DirectoryNode parent = traversal.parentNode;
        String lastPart = traversal.lastPart;

        FileSystemNode node = parent.getChild(lastPart);
        return node != null && node.isFile() == isFile;
    }
};

public class Main {
    public static void main(String[] args) {
        FileSystemManager fs = new FileSystemManager();

        // Create directories/files with parent auto-creation (createNewNodes = true)
        System.out.println("create /docs (dir, -p): " + fs.create("/docs", false, true));                 // true
        System.out.println("create /docs again (dir, -p): " + fs.create("/docs", false, true));           // true (same type exists)
        System.out.println("create /docs/readme.txt (file, -p): " + fs.create("/docs/readme.txt", true, true)); // true

        // Search (parents exist)
        System.out.println("search /docs as dir: " + fs.search("/docs", false));                          // true
        System.out.println("search /docs as file: " + fs.search("/docs", true));                          // false
        System.out.println("search /docs/readme.txt as file: " + fs.search("/docs/readme.txt", true));    // true

        // Conflict on type (try to create file at existing dir)
        System.out.println("create /docs as file (conflict): " + fs.create("/docs", true, true));         // false

        // Delete only if directory empty
        System.out.println("delete /docs (require empty=true): " + fs.delete("/docs", true));             // false (has readme.txt)
        System.out.println("delete /docs/readme.txt: " + fs.delete("/docs/readme.txt", true));            // true
        System.out.println("delete /docs (require empty=true): " + fs.delete("/docs", true));             // true

        // Create with parentMustExist behavior (createNewNodes = false)
        try {
            System.out.println("create /x/y (dir, parent must exist): " + fs.create("/x/y", false, false)); // throws (parent /x missing)
        } catch (RuntimeException e) {
            System.out.println("expected failure (parent missing): " + e.getMessage());
        }
        System.out.println("create /x (dir, parent must exist): " + fs.create("/x", false, false));        // true
        System.out.println("create /x/y (dir, parent must exist): " + fs.create("/x/y", false, false));    // true

        // Delete subtree pieces
        System.out.println("create /x/y/file.txt (file, -p off, parent now exists): " + fs.create("/x/y/file.txt", true, false)); // true
        System.out.println("delete /x/y (require empty=true): " + fs.delete("/x/y", true));                // false (not empty)
        System.out.println("delete /x/y/file.txt: " + fs.delete("/x/y/file.txt", true));                   // true
        System.out.println("delete /x/y (require empty=true): " + fs.delete("/x/y", true));                // true
        System.out.println("delete /x (require empty=false): " + fs.delete("/x", false));                  // true

        // Optional: demonstrate search throwing when parent path is missing (since search uses traversal without creation)
        try {
            System.out.println("search /missing/child as file: " + fs.search("/missing/child", true));
        } catch (RuntimeException e) {
            System.out.println("expected search failure (parent missing): " + e.getMessage());
        }
    }
};