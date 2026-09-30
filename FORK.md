# About this fork

Personal fork of [rebelonion/Dantotsu](https://git.rebelonion.dev/rebelonion/Dantotsu) carrying
manga reader and download fixes, Mangayomi extension support, and searching extensions for titles
AniList does not have. Everything else is upstream's work.

Upstream lives on Gitea, not GitHub, so this is not a GitHub fork and there is no pull request to
merge. The fixes are plain commits on `fix/reader-oom-and-detach`, replayed on top of upstream's
`dev` whenever upstream moves. That branch is the default branch here and gets force-pushed by the
sync job, so treat it as rebased history, not as something to merge into.

Kept deliberately small: every commit that stays is a commit that can conflict later.

## Staying current

`.github/workflows/sync-upstream.yml` runs at 03:10 UTC daily, and can be started by hand from the
Actions tab. It:

1. Fetches upstream `dev`.
2. Stops if this branch already contains it.
3. Rebases the fork commits onto it with `--empty=drop`, so any patch upstream has since adopted
   on its own disappears instead of lingering as an empty commit.
4. On success: force-pushes, builds `googleAlpha`, and publishes a release tagged
   `sync-<date>-<sha>` with the arm64 and universal APKs.
5. On conflict: aborts, changes nothing, and files (or comments on) an issue labelled
   `upstream-conflict` listing the conflicting files.

GitHub disables scheduled workflows on a repository with 60 days of no activity. If releases go
quiet, check the Actions tab.

Releases are signed with a keystore held in the repository secrets, so every build installs over
the last one. Lose that keystore and the next install needs an uninstall first, which means
backing up app data through the app's own backup screen.

## Doing it by hand

```bash
git fetch upstream dev
git rebase upstream/dev
git push --force-with-lease origin fix/reader-oom-and-detach
```

Resolving a conflict is normal rebase work: fix the files, `git add`, `git rebase --continue`. If a
fix has become unnecessary because upstream fixed the same thing, drop the commit instead of
merging the two.

## What is patched

### Manga reader

| Area | Change |
| --- | --- |
| Page loading | Compressed source bytes cached in `MangaCache` instead of re-downloading the page on every bind. The bitmap LRU it replaced was dead code. |
| Decoding | Subsampled to roughly twice the screen width, capped at 16M pixels, `RGB_565`. |
| Concurrency | `Semaphore(3)` around decodes. `getImage()` is a blocking OkHttp call on `Dispatchers.IO`, and cancelling the coroutine does not cancel the request. |
| Glide | `DiskCacheStrategy.DATA` instead of `NONE`, `PREFER_RGB_565`, `AT_MOST` size cap. |
| libvips | Full-resolution `ARGB_8888` result scaled down to the same ceiling. |
| Memory callbacks | `onTrimMemory` handled in `App` and `MangaReaderActivity`; both ignored every callback before. |
| Chapter loading | New reader setting **Continuous Chapters**, default off: one chapter in memory, transition screen at the end. On restores upstream's scroll-straight-through behaviour, with window trimming. |
| Crash | `MangaReadFragment.multiDownload` caught `CancellationException` in a generic `catch (e: Exception)` and then called `requireContext()` on a detached fragment. |
| Page progress | Page-progress writes no longer flood the heap. |
| Diagnostics | Long-press the page counter for an on-device heap report and thread dump. |

### Downloads

| Area | Change |
| --- | --- |
| Selection | Select several chapters or episodes, then download or delete them together. Long-press is selection again; a separate button opens the download manager. |
| Bulk delete | One pass, only items actually on disk; no crash on the shared downloads list. |
| FFmpegKit | nextlib (player decoders, ffmpeg 6) and ffmpeg-kit (downloads, ffmpeg 8) shipped `libavutil.so` and friends under the same names, and the ffmpeg 6 copies won, so every episode download crashed. nextlib 0.8.4 is vendored as `app/libs/nextlib-media3ext-0.8.4.jar` with its ffmpeg libs renamed to `libnx*.so` in `jniLibs/<abi>` (SONAME and `DT_NEEDED` patched in place). |

### Extensions

| Area | Change |
| --- | --- |
| Repo fetch | Stops a repo URL that redirects to itself from looping forever (the reader memory leak's real cause). |
| Mangayomi | JavaScript anime sources from Mangayomi repos run through a bridge (`parsers/mangayomi`) on quickjs-kt, which supports async/await. Its native lib ships renamed as `libquickjskt.so` because app.cash.quickjs, used by Aniyomi extensions, has the same file name. Dart sources and Mangayomi's hoster extractors are not supported. |
| Empty results | When a Mangayomi source finds no videos, the error names the request that failed. |

### Titles not on AniList

| Area | Change |
| --- | --- |
| Search | Search > Sources searches all installed extensions of the type at once (6 at a time, 25 s each), or one chosen extension. |
| Storage | Opened titles live in `extension_titles.json` with ids at or below -1000 (AniList ids are positive, local files use 0, home placeholders use -100 and down). |
| Details screen | No favourite or comments; the list button reads **Not on AniList**. Progress stays on the phone. |
| Home | Shown in Continue Watching / Reading once opened in the player or reader. |
| AniList | **Submit to AniList** opens `anilist.co/edit/{anime,manga}/new` in a WebView (AniList has no submission API). **Link to AniList** moves the source choice and progress to the chosen entry; an exact title match on AniList is offered automatically. |

Upstream's `beta.yml` is gated to `github.repository == 'rebelonion/Dantotsu'` so it skips here
instead of failing on secrets this repository does not have.

Bugs in upstream behaviour belong upstream, not here.
