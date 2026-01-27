{
  pkgs ? import <nixpkgs> {},
  unstable ? import <nixos-unstable> { inherit (pkgs) system; config.allowUnfree = true; }
}:


let
  jdk = pkgs.openjdk;
in

(pkgs.buildFHSUserEnv {
  name = "intellij-idea-scala";
  targetPkgs = pkgs: [
    pkgs.xorg.xauth
    pkgs.xorg.libXtst
    pkgs.glib
    pkgs.gtk3
    pkgs.jetbrains.idea-community
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
    pkgs.pinentry         # Passphrase entry for GPG
    pkgs.gzip             # Compression
    pkgs.xz               # Compression
    pkgs.bzip2            # Compression

    unstable.claude-code

  ];
}).env
