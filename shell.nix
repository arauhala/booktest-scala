{
  pkgs ? import <nixpkgs> { config.allowUnfree = true; }
}:

let
  # Signing wrapper: `gpg` that prompts on the TERMINAL instead of asking
  # gpg-agent for a pinentry program.
  #
  # Why this exists. gpg-agent finds its passphrase prompt through an ABSOLUTE
  # path in ~/.gnupg/gpg-agent.conf, not through PATH — so listing
  # pinentry-curses below does not help it, and when that path is a nix store
  # entry it dies at the next `nix-collect-garbage`. That is exactly what
  # happened here: the configured pinentry vanished and every signing attempt
  # failed with "No pinentry", which reads like a key problem and is not one.
  # (Proof it is not: signing with a deliberately wrong passphrase reports
  # "Bad passphrase", so the key is found, unwrapped and usable — only the
  # prompt is missing.)
  #
  # `--pinentry-mode loopback` takes gpg-agent's prompt out of the loop
  # entirely: gpg reads the passphrase from the controlling terminal itself.
  # It requires `allow-loopback-pinentry` in gpg-agent.conf, which is already
  # set. The agent still caches the result, so a multi-artifact publish
  # (~24 signatures across three Scala versions) asks once, not once each.
  #
  # This is a workaround scoped to this dev shell, NOT a repair. The durable
  # fix is a pinentry that cannot be collected — install one into your profile
  # and point gpg-agent.conf at it:
  #
  #     nix profile install nixpkgs#pinentry-curses
  #     # then set in ~/.gnupg/gpg-agent.conf:
  #     #   pinentry-program /home/<you>/.nix-profile/bin/pinentry-tty
  #     gpgconf --kill gpg-agent      # REQUIRED: the agent caches its config
  #                                   # at startup and ignores edits until then
  #
  # Named distinctly rather than shadowing `gpg`, so nothing else in the shell
  # silently changes behaviour; sbt-pgp picks it up through GPG_COMMAND below.
  gpg-loopback = pkgs.writeShellScriptBin "gpg-loopback" ''
    exec ${pkgs.gnupg}/bin/gpg --pinentry-mode loopback "$@"
  '';
in

(pkgs.buildFHSEnv {
  name = "intellij-idea-scala";
  targetPkgs = pkgs: [
    pkgs.xauth
    pkgs.libxtst
    pkgs.glib
    pkgs.gtk3
    pkgs.jetbrains.idea
    pkgs.vscode
    pkgs.openjdk
    pkgs.sbt
    pkgs.mill
    pkgs.scala
    pkgs.meld

    # Essential CLI utilities for Claude Code
    pkgs.ripgrep          # Fast grep alternative (rg command)
    pkgs.fd               # Fast find alternative
    pkgs.bat              # Better cat with syntax highlighting
    pkgs.tree             # Directory tree visualization
    pkgs.curl             # HTTP client
    pkgs.wget             # File downloader
    pkgs.unzip            # Archive extraction
    pkgs.zip              # Archive creation
    pkgs.gh               # GitHub CLI
    pkgs.git              # Version control
    pkgs.gnumake          # Build automation
    pkgs.file             # File type detection
    pkgs.which            # Command location finder
    pkgs.htop             # Process viewer
    pkgs.less             # Pager
    pkgs.vim              # Text editor
    pkgs.nano             # Simple text editor
    pkgs.rsync            # File synchronization
    pkgs.tmux             # Terminal multiplexer
    pkgs.screen           # Terminal multiplexer alternative
    pkgs.procps           # Process utilities (ps, top, etc.)
    pkgs.util-linux       # Core utilities
    pkgs.findutils        # Find utilities
    pkgs.coreutils        # Core GNU utilities
    pkgs.diffutils        # Diff utilities
    pkgs.gawk             # AWK text processing
    pkgs.gnused           # Stream editor
    pkgs.gnugrep          # Pattern matching
    pkgs.gnutar           # Archive tool
    pkgs.gnupg            # GPG for signing artifacts
    pkgs.pinentry-curses  # Passphrase entry for GPG
    gpg-loopback          # gpg that prompts on the TTY (see comment above)
    pkgs.gzip             # Compression
    pkgs.xz               # Compression
    pkgs.bzip2            # Compression

  ];

  profile = ''
    # gpg needs to know which terminal to prompt on.
    export GPG_TTY=$(tty 2>/dev/null || echo "")

    # sbt-pgp invokes whatever `gpgCommand` names; build.sbt reads this.
    export GPG_COMMAND=gpg-loopback

    # Say something only when the machine is actually in the broken state, so
    # this is a diagnosis rather than noise on every shell entry.
    _pe=$(awk '/^pinentry-program/{print $2}' "$HOME/.gnupg/gpg-agent.conf" 2>/dev/null | head -1)
    if [ -n "$_pe" ] && [ ! -x "$_pe" ]; then
      echo "note: ~/.gnupg/gpg-agent.conf points at a pinentry that no longer exists:"
      echo "      $_pe"
      echo "      (a nix-store path removed by garbage collection — your KEY is fine)"
      echo "      Signing in this shell works anyway: GPG_COMMAND=gpg-loopback prompts"
      echo "      on this terminal. See the comment in shell.nix for the durable fix."
    fi
    unset _pe
  '';
}).env
