# Managed by ansible (playbooks/k3s.yml). Default container profile of containerd (its template in
# containerd/contrib/apparmor), loaded BEFORE k3s starts so containerd keeps it instead of
# generating its own.
#
# Why: on Ubuntu 26.04 (AppArmor 5) container processes carry the stacked label
# "cri-containerd.apparmor.d//&unconfined", which containerd's peer=cri-containerd.apparmor.d signal
# rule does not match. Every signal inside a container was denied: Postgres hung in startup (it
# signals its own processes), vmagent crashed ("cannot send SIGHUP to itself"). Seen 2026-11-11.
# Change vs. the template: signals are allowed with any peer. Containers still cannot reach other
# containers' or host processes: they live in their own PID namespace.
#include <tunables/global>

profile cri-containerd.apparmor.d flags=(attach_disconnected,mediate_deleted) {
  #include <abstractions/base>

  network,
  capability,
  file,
  umount,
  signal (send, receive),

  deny @{PROC}/* w,   # deny write for all files directly in /proc (not in a subdir)
  # deny write to files not in /proc/<number>/** or /proc/sys/**
  deny @{PROC}/{[^1-9],[^1-9][^0-9],[^1-9s][^0-9y][^0-9s],[^1-9][^0-9][^0-9][^0-9/]*}/** w,
  deny @{PROC}/sys/[^k]** w,  # deny /proc/sys except /proc/sys/k* (effectively /proc/sys/kernel)
  deny @{PROC}/sys/kernel/{?,??,[^s][^h][^m]**} w,  # deny everything except shm* in /proc/sys/kernel/
  deny @{PROC}/sysrq-trigger rwklx,
  deny @{PROC}/kcore rwklx,

  deny mount,

  deny /sys/[^f]*/** wklx,
  deny /sys/f[^s]*/** wklx,
  deny /sys/fs/[^c]*/** wklx,
  deny /sys/fs/c[^g]*/** wklx,
  deny /sys/fs/cg[^r]*/** wklx,
  deny /sys/firmware/** rwklx,
  deny /sys/devices/virtual/powercap/** rwklx,
  deny /sys/kernel/security/** rwklx,

  ptrace (trace,read,tracedby,readby) peer=cri-containerd.apparmor.d,
}
