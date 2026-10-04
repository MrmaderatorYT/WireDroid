package com.ccs.wiredroid.git;

import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.PullCommand;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.RebaseResult;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Collections;

/**
 * High-level обгортка над JGit для роботи з локальним репозиторієм.
 * <p>Усі мережеві операції довгі — викликайте з фонового потоку.</p>
 */
public final class GitManager {

    public static final class GitStatus {
        public final Set<String> added       = new HashSet<>();
        public final Set<String> modified    = new HashSet<>();
        public final Set<String> changed     = new HashSet<>(); // у staging
        public final Set<String> removed     = new HashSet<>();
        public final Set<String> missing     = new HashSet<>();
        public final Set<String> untracked   = new HashSet<>();
        public final Set<String> conflicting = new HashSet<>();

        public boolean isEmpty() {
            return added.isEmpty() && modified.isEmpty() && changed.isEmpty()
                    && removed.isEmpty() && missing.isEmpty() && untracked.isEmpty()
                    && conflicting.isEmpty();
        }
        public int totalChanged() {
            return added.size() + modified.size() + changed.size() + removed.size()
                    + missing.size() + untracked.size() + conflicting.size();
        }
    }

    public static final class CommitInfo {
        public final String hash;
        public final String shortHash;
        public final String author;
        public final String email;
        public final long   timeMs;
        public final String message;
        public CommitInfo(String hash, String author, String email, long timeMs, String message) {
            this.hash = hash;
            this.shortHash = hash != null && hash.length() >= 7 ? hash.substring(0, 7) : hash;
            this.author = author;
            this.email = email;
            this.timeMs = timeMs;
            this.message = message;
        }
    }

    public static final class BranchInfo {
        public final String name;
        public final boolean current;
        public final boolean remote;
        public BranchInfo(String name, boolean current, boolean remote) {
            this.name = name; this.current = current; this.remote = remote;
        }
    }

    public static final class TagInfo {
        public final String name;
        public final String objectId;
        public TagInfo(String name, String objectId) {
            this.name = name;
            this.objectId = objectId;
        }
    }

    public static final class StashInfo {
        public final String id;
        public final String shortId;
        public final String message;
        public StashInfo(String id, String message) {
            this.id = id;
            this.shortId = id != null && id.length() > 7 ? id.substring(0, 7) : id;
            this.message = message;
        }
    }

    private GitManager() {}

    // ── Repository state ──────────────────────────────────────

    public static boolean isGitRepo(File dir) {
        if (dir == null) return false;
        File git = new File(dir, ".git");
        return git.exists() && git.isDirectory();
    }

    /** {@code git init} */
    public static void init(File dir) throws Exception {
        try (Git g = Git.init().setDirectory(dir).call()) {
            // Засіюємо ім'я гілки та користувача за потреби
            ensureUserConfig(g.getRepository(), null, null);
        }
    }

    /** {@code git status} */
    public static GitStatus status(File dir) throws Exception {
        try (Git g = Git.open(dir)) {
            Status s = g.status().call();
            GitStatus r = new GitStatus();
            r.added.addAll(s.getAdded());
            r.modified.addAll(s.getModified());
            r.changed.addAll(s.getChanged());
            r.removed.addAll(s.getRemoved());
            r.missing.addAll(s.getMissing());
            r.untracked.addAll(s.getUntracked());
            r.conflicting.addAll(s.getConflicting());
            return r;
        }
    }

    public static String currentBranch(File dir) throws Exception {
        try (Git g = Git.open(dir)) {
            return g.getRepository().getBranch();
        }
    }

    public static boolean isRebasing(File dir) {
        try (Git g = Git.open(dir)) {
            return g.getRepository().getRepositoryState().name().startsWith("REBASING");
        } catch (Exception ignored) {
            return false;
        }
    }

    // ── Staging & Commit ──────────────────────────────────────

    /** {@code git add .} (з видаленнями: addRemoved=true). */
    public static void addAll(File dir) throws Exception {
        try (Git g = Git.open(dir)) {
            AddCommand add = g.add().addFilepattern(".");
            add.call();
            // Окремо обробляємо видалені/відсутні файли
            try {
                Status s = g.status().call();
                for (String missing : s.getMissing()) {
                    g.rm().addFilepattern(missing).call();
                }
            } catch (Exception ignored) {}
        }
    }

    public static void addPath(File dir, String pattern) throws Exception {
        try (Git g = Git.open(dir)) {
            g.add().addFilepattern(pattern).call();
        }
    }

    public static void unstagePath(File dir, String pattern) throws Exception {
        try (Git g = Git.open(dir)) {
            g.reset().addPath(pattern).call();
        }
    }

    public static RevCommit commit(File dir, String message,
                                   String authorName, String authorEmail) throws Exception {
        try (Git g = Git.open(dir)) {
            ensureUserConfig(g.getRepository(), authorName, authorEmail);
            return g.commit()
                    .setMessage(message)
                    .setAuthor(safe(authorName, "WireDroid"), safe(authorEmail, "wiredroid@local"))
                    .setCommitter(safe(authorName, "WireDroid"), safe(authorEmail, "wiredroid@local"))
                    .call();
        }
    }

    // ── History ───────────────────────────────────────────────

    public static List<CommitInfo> log(File dir, int limit) throws Exception {
        try (Git g = Git.open(dir)) {
            List<CommitInfo> out = new ArrayList<>();
            int n = 0;
            for (RevCommit c : g.log().setMaxCount(Math.max(limit, 1)).call()) {
                String name  = c.getAuthorIdent() != null ? c.getAuthorIdent().getName()  : "?";
                String email = c.getAuthorIdent() != null ? c.getAuthorIdent().getEmailAddress() : "";
                long timeMs  = c.getCommitTime() * 1000L;
                String msg   = c.getShortMessage();
                out.add(new CommitInfo(c.getName(), name, email, timeMs, msg));
                if (++n >= limit) break;
            }
            return out;
        }
    }

    // ── Branches ──────────────────────────────────────────────

    public static List<BranchInfo> branches(File dir) throws Exception {
        try (Git g = Git.open(dir)) {
            String head = g.getRepository().getBranch();
            List<BranchInfo> out = new ArrayList<>();
            for (Ref ref : g.branchList().setListMode(ListBranchCommand.ListMode.ALL).call()) {
                String full = ref.getName();
                boolean remote = full.startsWith("refs/remotes/");
                String simple = full
                        .replaceFirst("^refs/heads/", "")
                        .replaceFirst("^refs/remotes/", "");
                boolean current = !remote && simple.equals(head);
                out.add(new BranchInfo(simple, current, remote));
            }
            return out;
        }
    }

    public static void checkoutBranch(File dir, String name) throws Exception {
        try (Git g = Git.open(dir)) {
            g.checkout().setName(name).call();
        }
    }

    public static void createBranch(File dir, String name) throws Exception {
        try (Git g = Git.open(dir)) {
            g.branchCreate().setName(name).call();
        }
    }

    public static void deleteBranch(File dir, String name) throws Exception {
        try (Git g = Git.open(dir)) {
            g.branchDelete().setBranchNames(name).setForce(true).call();
        }
    }

    // ── Advanced history operations ─────────────────────────

    public static String merge(File dir, String revision) throws Exception {
        try (Git g = Git.open(dir)) {
            org.eclipse.jgit.lib.ObjectId id = g.getRepository().resolve(revision);
            if (id == null) throw new IllegalArgumentException("Unknown revision: " + revision);
            MergeResult result = g.merge().include(id).call();
            return String.valueOf(result.getMergeStatus());
        }
    }

    public static String rebase(File dir, String upstream) throws Exception {
        try (Git g = Git.open(dir)) {
            RebaseResult result = g.rebase().setUpstream(upstream).call();
            return String.valueOf(result.getStatus());
        }
    }

    public static String continueRebase(File dir) throws Exception {
        try (Git g = Git.open(dir)) {
            return String.valueOf(g.rebase()
                    .setOperation(org.eclipse.jgit.api.RebaseCommand.Operation.CONTINUE)
                    .call().getStatus());
        }
    }

    public static String abortRebase(File dir) throws Exception {
        try (Git g = Git.open(dir)) {
            return String.valueOf(g.rebase()
                    .setOperation(org.eclipse.jgit.api.RebaseCommand.Operation.ABORT)
                    .call().getStatus());
        }
    }

    public static String cherryPick(File dir, String revision) throws Exception {
        try (Git g = Git.open(dir)) {
            org.eclipse.jgit.lib.ObjectId id = g.getRepository().resolve(revision);
            if (id == null) throw new IllegalArgumentException("Unknown revision: " + revision);
            return String.valueOf(g.cherryPick().include(id).call().getStatus());
        }
    }

    public static StashInfo createStash(File dir, String message) throws Exception {
        try (Git g = Git.open(dir)) {
            RevCommit commit = g.stashCreate()
                    .setWorkingDirectoryMessage(safe(message, "WireDroid stash"))
                    .setIncludeUntracked(true)
                    .call();
            return commit == null ? null : new StashInfo(commit.getName(), commit.getShortMessage());
        }
    }

    public static List<StashInfo> stashes(File dir) throws Exception {
        try (Git g = Git.open(dir)) {
            List<StashInfo> result = new ArrayList<>();
            for (RevCommit commit : g.stashList().call()) {
                result.add(new StashInfo(commit.getName(), commit.getShortMessage()));
            }
            return result;
        }
    }

    public static void applyStash(File dir, String stashId) throws Exception {
        try (Git g = Git.open(dir)) {
            g.stashApply().setStashRef(stashId).call();
        }
    }

    public static void dropStash(File dir, int index) throws Exception {
        try (Git g = Git.open(dir)) {
            g.stashDrop().setStashRef(index).call();
        }
    }

    public static List<TagInfo> tags(File dir) throws Exception {
        try (Git g = Git.open(dir)) {
            List<TagInfo> result = new ArrayList<>();
            for (Ref ref : g.tagList().call()) {
                result.add(new TagInfo(Repository.shortenRefName(ref.getName()),
                        ref.getObjectId() == null ? "" : ref.getObjectId().name()));
            }
            return result;
        }
    }

    public static void createTag(File dir, String name, String message) throws Exception {
        try (Git g = Git.open(dir)) {
            org.eclipse.jgit.api.TagCommand command = g.tag().setName(name);
            if (message != null && !message.trim().isEmpty()) {
                command.setAnnotated(true).setMessage(message.trim());
            }
            command.call();
        }
    }

    public static void deleteTag(File dir, String name) throws Exception {
        try (Git g = Git.open(dir)) {
            g.tagDelete().setTags(name).call();
        }
    }

    // ── Remotes / Network ─────────────────────────────────────

    public static void setRemoteOrigin(File dir, String url) throws Exception {
        try (Git g = Git.open(dir)) {
            StoredConfig cfg = g.getRepository().getConfig();
            cfg.setString("remote", "origin", "url", url);
            cfg.setString("remote", "origin", "fetch",
                    "+refs/heads/*:refs/remotes/origin/*");
            cfg.save();
        }
    }

    public static String remoteOriginUrl(File dir) throws Exception {
        try (Git g = Git.open(dir)) {
            return g.getRepository().getConfig().getString("remote", "origin", "url");
        }
    }

    public static void clone(String url, File dest, String username, String password, org.eclipse.jgit.lib.ProgressMonitor monitor) throws Exception {
        clone(url, dest, username, password, null, false, monitor);
    }

    /**
     * Clone, optionally narrowed to one branch.
     *
     * <p>JGit 5.13 has no {@code setDepth} — shallow clone arrived in JGit 6 —
     * so the only way to make a large repository practical on a phone is to
     * fetch a single branch and no tags. On a repository with a long history
     * that is a smaller saving than {@code --depth 1}, but it is the whole
     * saving available here, and it is the difference between one branch's
     * objects and every branch's.</p>
     *
     * @param branch       branch to check out, or {@code null} for the remote default
     * @param singleBranch fetch only {@code branch} and skip tags; ignored when
     *                     {@code branch} is {@code null}, since JGit cannot name
     *                     the default branch without asking the remote first
     */
    public static void clone(String url, File dest, String username, String password,
                             String branch, boolean singleBranch,
                             org.eclipse.jgit.lib.ProgressMonitor monitor) throws Exception {
        if (monitor == null) {
            monitor = org.eclipse.jgit.lib.NullProgressMonitor.INSTANCE;
        }

        CloneCommand cmd = Git.cloneRepository()
                .setURI(url)
                .setDirectory(dest)
                .setTimeout(30)
                .setProgressMonitor(monitor);
        if (branch != null && !branch.isEmpty()) {
            cmd.setBranch(branch);
            if (singleBranch) {
                cmd.setCloneAllBranches(false);
                cmd.setBranchesToClone(java.util.Collections.singletonList(
                        branch.startsWith("refs/") ? branch : Constants.R_HEADS + branch));
                cmd.setNoTags();
            }
        }
        if (username != null && !username.isEmpty()) {
            cmd.setCredentialsProvider(new UsernamePasswordCredentialsProvider(
                    username, password == null ? "" : password));
        }
        try (Git g = cmd.call()) {
            ensureUserConfig(g.getRepository(), null, null);
        }
    }

    /**
     * Asks the remote which branch its {@code HEAD} points at, without cloning.
     *
     * <p>Needed before a single-branch clone: naming a branch is what makes the
     * narrow fetch possible, and "whatever the default is" is not a name. Modern
     * servers advertise {@code HEAD} as a symbolic ref; for the ones that do not,
     * the branch is found by matching object ids.</p>
     *
     * @return a short branch name, or {@code null} when the remote did not say
     */
    public static String defaultBranch(String url, String username, String password)
            throws Exception {
        org.eclipse.jgit.api.LsRemoteCommand cmd = Git.lsRemoteRepository()
                .setRemote(url)
                .setTimeout(30);
        if (username != null && !username.isEmpty()) {
            cmd.setCredentialsProvider(new UsernamePasswordCredentialsProvider(
                    username, password == null ? "" : password));
        }
        Ref head = null;
        List<Ref> heads = new ArrayList<>();
        for (Ref ref : cmd.call()) {
            if (Constants.HEAD.equals(ref.getName())) head = ref;
            else if (ref.getName().startsWith(Constants.R_HEADS)) heads.add(ref);
        }
        if (head == null) return null;
        if (head.isSymbolic() && head.getTarget() != null
                && head.getTarget().getName().startsWith(Constants.R_HEADS)) {
            return head.getTarget().getName().substring(Constants.R_HEADS.length());
        }
        if (head.getObjectId() == null) return null;
        for (Ref ref : heads) {
            if (head.getObjectId().equals(ref.getObjectId())) {
                return ref.getName().substring(Constants.R_HEADS.length());
            }
        }
        return null;
    }

    public static String pull(File dir, String username, String password) throws Exception {
        try (Git g = Git.open(dir)) {
            PullCommand cmd = g.pull();
            if (username != null && !username.isEmpty()) {
                cmd.setCredentialsProvider(new UsernamePasswordCredentialsProvider(
                        username, password == null ? "" : password));
            }
            PullResult r = cmd.call();
            StringBuilder sb = new StringBuilder();
            sb.append("Fetch: ").append(r.getFetchResult() == null ? "—" : r.getFetchResult().getMessages());
            sb.append("\nMerge status: ");
            if (r.getMergeResult() != null) {
                sb.append(r.getMergeResult().getMergeStatus());
                if (r.getMergeResult().getConflicts() != null
                        && !r.getMergeResult().getConflicts().isEmpty()) {
                    sb.append("\nConflicts: ").append(r.getMergeResult().getConflicts().keySet());
                }
            } else if (r.getRebaseResult() != null) {
                sb.append("rebase ").append(r.getRebaseResult().getStatus());
            } else {
                sb.append("—");
            }
            return sb.toString();
        }
    }

    public static String push(File dir, String username, String password) throws Exception {
        try (Git g = Git.open(dir)) {
            PushCommand cmd = g.push();
            if (username != null && !username.isEmpty()) {
                cmd.setCredentialsProvider(new UsernamePasswordCredentialsProvider(
                        username, password == null ? "" : password));
            }
            // Якщо upstream ще не налаштовано — пушимо поточну гілку явно
            String head = g.getRepository().getBranch();
            cmd.setRefSpecs(new org.eclipse.jgit.transport.RefSpec(head + ":" + head));
            Iterable<PushResult> results = cmd.call();
            StringBuilder sb = new StringBuilder();
            for (PushResult r : results) {
                sb.append("→ ").append(r.getURI()).append('\n');
                for (RemoteRefUpdate u : r.getRemoteUpdates()) {
                    sb.append("  ").append(u.getRemoteName())
                            .append(" : ").append(u.getStatus());
                    if (u.getMessage() != null) sb.append(" (").append(u.getMessage()).append(')');
                    sb.append('\n');
                }
                if (r.getMessages() != null && !r.getMessages().isEmpty()) {
                    sb.append("  ").append(r.getMessages()).append('\n');
                }
            }
            return sb.toString();
        }
    }

    // ── Helpers ───────────────────────────────────────────────

    private static void ensureUserConfig(Repository repo, String name, String email) throws Exception {
        StoredConfig cfg = repo.getConfig();
        String existingName  = cfg.getString(ConfigConstants.CONFIG_USER_SECTION, null,
                ConfigConstants.CONFIG_KEY_NAME);
        String existingEmail = cfg.getString(ConfigConstants.CONFIG_USER_SECTION, null,
                ConfigConstants.CONFIG_KEY_EMAIL);
        boolean dirty = false;
        if (existingName == null || existingName.isEmpty()) {
            cfg.setString(ConfigConstants.CONFIG_USER_SECTION, null,
                    ConfigConstants.CONFIG_KEY_NAME, safe(name, "WireDroid"));
            dirty = true;
        }
        if (existingEmail == null || existingEmail.isEmpty()) {
            cfg.setString(ConfigConstants.CONFIG_USER_SECTION, null,
                    ConfigConstants.CONFIG_KEY_EMAIL, safe(email, "wiredroid@local"));
            dirty = true;
        }
        if (dirty) cfg.save();
    }

    private static String safe(String s, String fallback) {
        return (s == null || s.trim().isEmpty()) ? fallback : s.trim();
    }

    /** Перевіряє, що URL виглядає як правильний Git URI. */
    public static boolean isValidGitUrl(String url) {
        if (url == null || url.trim().isEmpty()) return false;
        try {
            new URIish(url.trim());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Витягує назву останнього сегмента з url для назви папки клону. */
    public static String repoNameFromUrl(String url) {
        if (url == null) return "repo";
        String s = url.trim();
        if (s.endsWith(".git")) s = s.substring(0, s.length() - 4);
        int slash = s.lastIndexOf('/');
        if (slash >= 0 && slash < s.length() - 1) s = s.substring(slash + 1);
        s = s.replaceAll("[^A-Za-z0-9._-]", "_");
        return s.isEmpty() ? "repo" : s;
    }

    /** Намагається визначити, чи є невипушені коміти на поточній гілці. */
    public static int aheadCount(File dir) {
        try (Git g = Git.open(dir)) {
            org.eclipse.jgit.lib.BranchTrackingStatus st = org.eclipse.jgit.lib.BranchTrackingStatus
                    .of(g.getRepository(), g.getRepository().getBranch());
            return st == null ? 0 : st.getAheadCount();
        } catch (Exception e) {
            return 0;
        }
    }

    public static int behindCount(File dir) {
        try (Git g = Git.open(dir)) {
            org.eclipse.jgit.lib.BranchTrackingStatus st = org.eclipse.jgit.lib.BranchTrackingStatus
                    .of(g.getRepository(), g.getRepository().getBranch());
            return st == null ? 0 : st.getBehindCount();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Прапорець, чи є HEAD коміт у репозиторії. */
    public static boolean hasHead(File dir) {
        try (Git g = Git.open(dir)) {
            return g.getRepository().resolve(Constants.HEAD) != null;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Diff ────────────────────────────────────────────────

    private static CanonicalTreeParser treeIterator(Repository repo, RevWalk walk, RevCommit commit) throws Exception {
        CanonicalTreeParser parser = new CanonicalTreeParser();
        parser.reset(walk.getObjectReader(), commit.getTree());
        return parser;
    }

    private static RevCommit resolveHead(Repository repo, RevWalk walk) throws Exception {
        org.eclipse.jgit.lib.ObjectId headId = repo.resolve(Constants.HEAD);
        if (headId == null) return null;
        return walk.parseCommit(headId);
    }

    /** Повертає unified diff поточних змін (HEAD vs working tree). */
    public static String diffWorkingTree(File dir) throws Exception {
        try (Git g = Git.open(dir)) {
            Repository repo = g.getRepository();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (DiffFormatter fmt = new DiffFormatter(out)) {
                fmt.setRepository(repo);
                fmt.setOldPrefix("a/");
                fmt.setNewPrefix("b/");
                try (RevWalk walk = new RevWalk(repo)) {
                    RevCommit head = resolveHead(repo, walk);
                    if (head == null) return "";
                    CanonicalTreeParser headTree = treeIterator(repo, walk, head);
                    List<DiffEntry> entries = fmt.scan(headTree, new FileTreeIterator(repo));
                    for (DiffEntry entry : entries) fmt.format(entry);
                }
            }
            return out.toString("UTF-8");
        }
    }

    /** Повертає unified diff staged змін (HEAD vs index). */
    public static String diffStaged(File dir) throws Exception {
        try (Git g = Git.open(dir)) {
            Repository repo = g.getRepository();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (DiffFormatter fmt = new DiffFormatter(out)) {
                fmt.setRepository(repo);
                fmt.setOldPrefix("a/");
                fmt.setNewPrefix("b/");
                DirCacheIterator index = new DirCacheIterator(repo.readDirCache());
                try (RevWalk walk = new RevWalk(repo)) {
                    RevCommit head = resolveHead(repo, walk);
                    if (head == null) {
                        List<DiffEntry> entries = fmt.scan(new FileTreeIterator(repo), index);
                        for (DiffEntry entry : entries) fmt.format(entry);
                    } else {
                        CanonicalTreeParser headTree = treeIterator(repo, walk, head);
                        List<DiffEntry> entries = fmt.scan(headTree, index);
                        for (DiffEntry entry : entries) fmt.format(entry);
                    }
                }
            }
            return out.toString("UTF-8");
        }
    }

    /** Повертає unified diff для конкретного файлу (HEAD vs working tree). */
    public static String diffFile(File dir, String path) throws Exception {
        try (Git g = Git.open(dir)) {
            Repository repo = g.getRepository();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (DiffFormatter fmt = new DiffFormatter(out)) {
                fmt.setRepository(repo);
                fmt.setOldPrefix("a/");
                fmt.setNewPrefix("b/");
                try (RevWalk walk = new RevWalk(repo)) {
                    RevCommit head = resolveHead(repo, walk);
                    if (head == null) return "";
                    CanonicalTreeParser headTree = treeIterator(repo, walk, head);
                    List<DiffEntry> entries = fmt.scan(headTree, new FileTreeIterator(repo));
                    for (DiffEntry entry : entries) {
                        if (entry.getOldPath().equals(path) || entry.getNewPath().equals(path)) {
                            fmt.format(entry);
                        }
                    }
                }
            }
            return out.toString("UTF-8");
        }
    }

    /** Повертає diff між двома комітами (commitA..commitB). */
    public static String diffCommits(File dir, String commitA, String commitB) throws Exception {
        try (Git g = Git.open(dir)) {
            Repository repo = g.getRepository();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (DiffFormatter fmt = new DiffFormatter(out)) {
                fmt.setRepository(repo);
                fmt.setOldPrefix("a/");
                fmt.setNewPrefix("b/");
                try (RevWalk walk = new RevWalk(repo)) {
                    RevCommit cA = walk.parseCommit(repo.resolve(commitA));
                    RevCommit cB = walk.parseCommit(repo.resolve(commitB));
                    CanonicalTreeParser treeA = treeIterator(repo, walk, cA);
                    CanonicalTreeParser treeB = treeIterator(repo, walk, cB);
                    List<DiffEntry> entries = fmt.scan(treeA, treeB);
                    for (DiffEntry entry : entries) fmt.format(entry);
                }
            }
            return out.toString("UTF-8");
        }
    }

    /** Повертає список змінених файлів (HEAD vs working tree). */
    public static List<String> changedFiles(File dir) throws Exception {
        try (Git g = Git.open(dir)) {
            Repository repo = g.getRepository();
            List<String> files = new ArrayList<>();
            try (DiffFormatter fmt = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
                fmt.setRepository(repo);
                try (RevWalk walk = new RevWalk(repo)) {
                    RevCommit head = resolveHead(repo, walk);
                    if (head == null) return files;
                    CanonicalTreeParser headTree = treeIterator(repo, walk, head);
                    List<DiffEntry> entries = fmt.scan(headTree, new FileTreeIterator(repo));
                    for (DiffEntry entry : entries) files.add(entry.getNewPath());
                }
            }
            return files;
        }
    }

    /** Повертає кількість доданих/видалених/змінених рядків для файлу. */
    public static int[] diffStats(File dir, String path) throws Exception {
        try (Git g = Git.open(dir)) {
            Repository repo = g.getRepository();
            int added = 0, removed = 0;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (DiffFormatter fmt = new DiffFormatter(buf)) {
                fmt.setRepository(repo);
                fmt.setOldPrefix("a/");
                fmt.setNewPrefix("b/");
                try (RevWalk walk = new RevWalk(repo)) {
                    RevCommit head = resolveHead(repo, walk);
                    if (head == null) return new int[]{0, 0};
                    CanonicalTreeParser headTree = treeIterator(repo, walk, head);
                    List<DiffEntry> entries = fmt.scan(headTree, new FileTreeIterator(repo));
                    for (DiffEntry entry : entries) {
                        if (entry.getNewPath().equals(path)) {
                            buf.reset();
                            fmt.format(entry);
                            String diff = buf.toString("UTF-8");
                            for (String line : diff.split("\n")) {
                                if (line.startsWith("+") && !line.startsWith("+++")) added++;
                                else if (line.startsWith("-") && !line.startsWith("---")) removed++;
                            }
                        }
                    }
                }
            }
            return new int[]{added, removed};
        }
    }
}
