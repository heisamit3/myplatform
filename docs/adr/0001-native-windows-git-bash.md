# ADR 0001: Develop on native Windows with Git Bash, not inside WSL2

- Status: accepted
- Date: 2026-10-06

## Context

The original plan ran Claude Code and all tooling inside a WSL2 distro, with code in `~/projects`.
In practice Claude Code, Docker Desktop, kubectl and minikube were already installed and working on Windows,
and the project sat in a OneDrive folder.

## Decision

- Use native Windows 11 with Git Bash as the shell. Install tools with winget.
- Keep the repo in `C:\dev\myplatform`, outside OneDrive (sync locks and rewrites files under `.git`, `build/`, `node_modules/`).
- Docker Desktop (WSL2 backend) still runs all containers and minikube. No `.wslconfig`: its VM uses WSL2's default memory limit (about 3.7 GB).
- `.gitattributes` forces LF so scripts and Dockerfiles work inside Linux containers.

## Consequences

- No `free`/`sudo`; use `scripts/mem.sh` and treat UAC prompts as at-the-PC work.
- Git Bash rewrites POSIX paths passed to Windows binaries: docker/kubectl commands with container paths need `MSYS_NO_PATHCONV=1`.
- Bind mounts from the Windows filesystem are slower than from a WSL2 filesystem. Acceptable for this project's size.
