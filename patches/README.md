# Patches for projects without a GitHub fork

Most patched projects are GitHub forks with a `gd1` branch (see [docs/TECHNICAL.md](../docs/TECHNICAL.md)). A project that only exists on AOSP's Gerrit can't be forked on GitHub, so its patches are kept here as `git format-patch` files.

| Project | Base | Patch |
|---|---|---|
| `system/unwinding` | AOSP `8e95104` (android-14.0.0_r67, as pinned by LineageOS-UL) | `system_unwinding/0001-…`: libunwindstack falls back to `PTRACE_GETREGS` when `PTRACE_GETREGSET` fails with EIO, as on 32-bit ARM 3.4 kernels. Without it, native crashes have no backtrace. |

Apply after `repo sync`:

```
cd system/unwinding
git am ../../<path to this repo>/patches/system_unwinding/*.patch
```
