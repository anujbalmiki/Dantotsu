# About this fork

Personal fork of [rebelonion/Dantotsu](https://git.rebelonion.dev/rebelonion/Dantotsu) carrying a
small set of manga reader fixes. Everything else is upstream's work.

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

All of it is in the manga reader.

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

Upstream's `beta.yml` is gated to `github.repository == 'rebelonion/Dantotsu'` so it skips here
instead of failing on secrets this repository does not have.

Bugs in upstream behaviour belong upstream, not here.
