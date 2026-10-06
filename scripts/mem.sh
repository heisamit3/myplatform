#!/usr/bin/env bash
# Memory check for Git Bash on Windows (replaces `free -h`).
set -euo pipefail

echo "== Windows host"
powershell.exe -NoProfile -Command \
  '$o = Get-CimInstance Win32_OperatingSystem; "{0:N1} GB free of {1:N1} GB" -f ($o.FreePhysicalMemory/1MB), ($o.TotalVisibleMemorySize/1MB)'

echo "== Docker Desktop VM"
docker info --format '{{.MemTotal}}' | awk '{printf "%.1f GB allocated to the VM\n", $1/1024/1024/1024}'

echo "== Containers"
docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}'
