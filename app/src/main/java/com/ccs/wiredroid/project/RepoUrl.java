package com.ccs.wiredroid.project;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Everything people actually paste when they mean "this repository", reduced to
 * a clone URL.
 *
 * <p>A pasted link is rarely the clone URL. It is the page someone was looking
 * at: a branch listing, a file, a commit. All of those carry the repository
 * inside them, plus — for {@code tree}/{@code blob} style links — the branch
 * that was open at the time, which is worth keeping: cloning the default branch
 * when the user pasted a link to {@code dev} is the wrong repository as far as
 * they are concerned.</p>
 *
 * <p>SSH remotes are rewritten to HTTPS. There is no SSH key handling in the
 * app, so an {@code scp}-style remote would fail at authentication with an
 * error that says nothing about the cause; over HTTPS the same repository
 * clones anonymously when public, and with the token field when not.</p>
 */
public final class RepoUrl {

    /** Host family, used only to label what was recognised. */
    public enum Host { GITHUB, GITLAB, BITBUCKET, OTHER }

    /** HTTPS clone URL, always ending in {@code .git}. */
    public final String cloneUrl;
    public final String host;
    public final Host hostKind;
    /** Owner (or the full group path on GitLab), never empty. */
    public final String owner;
    public final String repo;
    /** Branch named by the link, or {@code null} for the repository default. */
    public final String branch;

    private RepoUrl(String cloneUrl, String host, Host hostKind,
                    String owner, String repo, String branch) {
        this.cloneUrl = cloneUrl;
        this.host = host;
        this.hostKind = hostKind;
        this.owner = owner;
        this.repo = repo;
        this.branch = branch;
    }

    /** Folder name to clone into: the repository name, filesystem-safe. */
    public String suggestedFolderName() {
        String s = repo.replaceAll("[^A-Za-z0-9._-]", "_");
        return s.isEmpty() ? "repo" : s;
    }

    /** {@code owner/repo} for display. */
    public String slug() {
        return owner + "/" + repo;
    }

    @Override
    public String toString() {
        return cloneUrl + (branch == null ? "" : " @" + branch);
    }

    // ── Parsing ─────────────────────────────────────────────────────────────

    /**
     * Path segments that end the repository path and start a view of it. The
     * repository is whatever came before the first one of these; anything after
     * belongs to the web UI, not to the clone URL.
     */
    private static final Set<String> VIEW_MARKERS = new HashSet<>(Arrays.asList(
            "-",            // GitLab inserts this before every view: /-/tree/dev
            "tree", "blob", "raw", "blame", "src", "commit", "commits",
            "releases", "tags", "branches", "issues", "pull", "pulls",
            "merge_requests", "compare", "archive", "wiki", "actions",
            "pipelines", "settings", "downloads", "pull-requests"));

    /** Of those, the ones followed by a ref: {@code /tree/dev}, {@code /src/dev/…}. */
    private static final Set<String> REF_MARKERS = new HashSet<>(Arrays.asList(
            "tree", "blob", "raw", "blame", "src", "branch"));

    /**
     * @param raw anything the user typed or pasted
     * @return the resolved repository, or {@code null} when this is not a
     *         repository URL the app can clone
     */
    public static RepoUrl parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        // Markdown/mail clients love wrapping links in angle brackets.
        if (s.startsWith("<") && s.endsWith(">")) s = s.substring(1, s.length() - 1).trim();
        int hash = s.indexOf('#');
        if (hash >= 0) s = s.substring(0, hash);
        int query = s.indexOf('?');
        if (query >= 0) s = s.substring(0, query);
        s = s.trim();
        if (s.isEmpty()) return null;

        // A self-hosted server reached over plain HTTP stays on plain HTTP;
        // upgrading it would fail against a host with no TLS at all.
        String scheme = s.toLowerCase(Locale.ROOT).startsWith("http://") ? "http" : "https";

        String hostAndPath = stripScheme(s);
        if (hostAndPath == null) return null;

        int slash = hostAndPath.indexOf('/');
        if (slash < 0) {
            return null; // a bare host names no repository
        }
        String authority = stripUserInfo(hostAndPath.substring(0, slash))
                .toLowerCase(Locale.ROOT);
        String path = hostAndPath.substring(slash + 1);

        // The port belongs in the clone URL — self-hosted GitLab on :8080 is a
        // different address from the same host on :443.
        String host = authority;
        String port = "";
        int colon = host.indexOf(':');
        if (colon >= 0) {
            port = host.substring(colon);
            host = host.substring(0, colon);
            if (!port.matches("^:\\d+$")) return null;
        }
        if (host.startsWith("www.")) {
            host = host.substring(4);
        }
        if (!isHostName(host)) return null;
        authority = host + port;

        List<String> segments = new ArrayList<>();
        for (String part : path.split("/")) {
            if (!part.isEmpty()) segments.add(part);
        }
        if (segments.size() < 2) return null;
        for (String part : segments) {
            if (!isSegment(part)) return null;
        }

        // The repository path is everything before the first view marker, but
        // never fewer than the two segments every host needs (owner + repo);
        // a repository legitimately named "src" or "tree" stays reachable.
        int cut = segments.size();
        for (int i = 2; i < segments.size(); i++) {
            if (VIEW_MARKERS.contains(segments.get(i).toLowerCase(Locale.ROOT))) {
                cut = i;
                break;
            }
        }
        String branch = branchAfter(segments, cut);

        List<String> repoPath = new ArrayList<>(segments.subList(0, cut));
        if (repoPath.size() < 2) return null;
        String last = repoPath.get(repoPath.size() - 1);
        if (last.toLowerCase(Locale.ROOT).endsWith(".git")) {
            last = last.substring(0, last.length() - 4);
            if (last.isEmpty()) return null;
            repoPath.set(repoPath.size() - 1, last);
        }

        StringBuilder joined = new StringBuilder();
        for (String part : repoPath) {
            if (joined.length() > 0) joined.append('/');
            joined.append(part);
        }
        String owner = joined.substring(0, joined.length() - last.length() - 1);

        return new RepoUrl(scheme + "://" + authority + "/" + joined + ".git",
                host, hostKind(host), owner, last, branch);
    }

    /**
     * The ref named by a view marker, if it names one.
     *
     * <p>GitLab writes {@code /-/tree/dev}, so the marker at the cut may be the
     * separator with the real marker one segment further on. A branch with a
     * slash in it ({@code feature/x}) is indistinguishable from a branch plus a
     * file path, so only the first segment is taken.</p>
     */
    private static String branchAfter(List<String> segments, int cut) {
        int i = cut;
        if (i >= segments.size()) return null;
        if ("-".equals(segments.get(i))) i++;
        if (i >= segments.size()) return null;
        String marker = segments.get(i).toLowerCase(Locale.ROOT);
        if (!REF_MARKERS.contains(marker)) return null;
        if (i + 1 >= segments.size()) return null;
        String ref = segments.get(i + 1);
        return ref.isEmpty() ? null : ref;
    }

    /**
     * Reduces any accepted form to {@code host/path}.
     *
     * @return {@code null} for schemes the app cannot clone over
     */
    private static String stripScheme(String s) {
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.startsWith("https://")) return s.substring(8);
        if (lower.startsWith("http://")) return s.substring(7);
        if (lower.startsWith("ssh://") || lower.startsWith("git://")) {
            // Same repository, fetched over HTTPS instead — see class javadoc.
            return stripUserInfoFromAuthority(s.substring(6));
        }
        if (lower.startsWith("git+https://")) return s.substring(12);
        if (lower.contains("://")) return null; // file://, ftp://, anything else

        // scp-style: git@github.com:user/repo.git
        int at = s.indexOf('@');
        int colon = s.indexOf(':');
        if (colon > 0 && (at < 0 || at < colon)) {
            String authority = s.substring(0, colon);
            String path = s.substring(colon + 1);
            if (path.isEmpty() || path.startsWith("/")) return null;
            // "host:8080/user/repo" is a port, not an scp path.
            if (!path.matches("^\\d+/.*")) {
                return stripUserInfo(authority) + "/" + path;
            }
        }

        // Bare "user/repo" — GitHub is the only host it can mean.
        if (s.matches("^[A-Za-z0-9](?:[A-Za-z0-9._-]*)/[A-Za-z0-9](?:[A-Za-z0-9._-]*)$")) {
            return "github.com/" + s;
        }
        // "github.com/user/repo" without a scheme.
        int slash = s.indexOf('/');
        if (slash > 0 && s.substring(0, slash).contains(".")) {
            return s;
        }
        return null;
    }

    private static String stripUserInfoFromAuthority(String hostAndPath) {
        int slash = hostAndPath.indexOf('/');
        if (slash < 0) return stripUserInfo(hostAndPath);
        return stripUserInfo(hostAndPath.substring(0, slash)) + hostAndPath.substring(slash);
    }

    private static String stripUserInfo(String authority) {
        int at = authority.lastIndexOf('@');
        return at >= 0 ? authority.substring(at + 1) : authority;
    }

    private static boolean isHostName(String host) {
        return host.contains(".")
                && host.matches("^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$");
    }

    private static boolean isSegment(String part) {
        return part.matches("^[A-Za-z0-9._~%+@-]+$");
    }

    private static Host hostKind(String host) {
        if (host.equals("github.com") || host.endsWith(".github.com")) return Host.GITHUB;
        if (host.equals("gitlab.com") || host.startsWith("gitlab.")) return Host.GITLAB;
        if (host.equals("bitbucket.org") || host.startsWith("bitbucket.")) return Host.BITBUCKET;
        return Host.OTHER;
    }
}
