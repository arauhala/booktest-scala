#!/usr/bin/env bash
set -e

# Booktest-scala development helper script

show_help() {
    echo "Usage: ./do <command>"
    echo ""
    echo "Commands:"
    echo "  build       Compile the project"
    echo "  test        Run example tests"
    echo "  publish     Sign and publish to Maven Central"
    echo "  help        Show this help message"
    echo ""
    echo "Examples:"
    echo "  ./do build"
    echo "  ./do test                    # Run default group from booktest.conf"
    echo "  ./do test examples           # Run 'examples' group from booktest.conf"
    echo "  ./do test ExampleTests       # Run specific test suite"
    echo "  ./do publish"
}

cmd_build() {
    sbt compile
}

cmd_test() {
    # Pass all arguments to BooktestMain
    if [[ $# -eq 0 ]]; then
        # No args - use default group from booktest.conf
        sbt "Test/runMain booktest.BooktestMain"
    else
        # Pass all arguments directly
        sbt "Test/runMain booktest.BooktestMain $*"
    fi
}

cmd_publish() {
    echo "==> Setting up GPG environment..."
    export GPG_TTY=$(tty)

    # Pre-flight: a signing setup that cannot prompt for a passphrase fails
    # AFTER cross-compiling every Scala version, which is a long wait for a
    # one-line problem. Check it in a second instead.
    _pe=$(awk '/^pinentry-program/{print $2}' "$HOME/.gnupg/gpg-agent.conf" 2>/dev/null | head -1)
    if [ -n "$_pe" ] && [ ! -x "$_pe" ] && [ -z "${GPG_COMMAND:-}" ]; then
        echo "ERROR: gpg cannot ask you for a passphrase."
        echo "  ~/.gnupg/gpg-agent.conf points at a pinentry that does not exist:"
        echo "    $_pe"
        echo "  (a nix-store path removed by garbage collection — your key is fine)"
        echo
        echo "  Fix either way:"
        echo "    - run this inside 'nix-shell' here, which sets GPG_COMMAND=gpg-loopback, or"
        echo "    - install a pinentry that survives GC and point gpg-agent.conf at it,"
        echo "      then 'gpgconf --kill gpg-agent' (the agent caches its config at startup)."
        exit 1
    fi
    unset _pe

    # Both of these bit us on a real release, and both are knowable up front.
    _version=$(grep -oP 'ThisBuild / version := "\K[^"]+' build.sbt | head -1)
    if [ -z "$_version" ]; then
        echo "ERROR: could not read the version from build.sbt"; exit 1
    fi
    echo "==> Version: $_version"

    # 1. Already released? Central is immutable, so publishing an existing
    #    version rebuilds and re-signs everything, warns "attempting to
    #    overwrite (non-SNAPSHOT)" for every artifact, and cannot succeed. The
    #    usual cause is simply forgetting to bump.
    _url="https://repo1.maven.org/maven2/io/github/arauhala/booktest-scala_2.12/$_version/booktest-scala_2.12-$_version.pom"
    if [ "$(curl -s -o /dev/null -w '%{http_code}' "$_url")" = "200" ]; then
        echo "ERROR: $_version is already on Maven Central and cannot be replaced."
        echo "  Bump 'ThisBuild / version' in build.sbt (and the README dependency"
        echo "  line), add a CHANGELOG entry, then publish."
        exit 1
    fi

    # 2. A leftover staging directory makes the bundle step fail with
    #    BUNDLE_ZIP_ERROR after all the signing work is already done.
    if [ -d target/sonatype-staging ]; then
        echo "==> Clearing stale staging directories: $(ls target/sonatype-staging | tr '\n' ' ')"
        rm -rf target/sonatype-staging
    fi

    echo "==> Publishing signed artifacts for all Scala versions..."
    sbt +publishSigned

    echo "==> Uploading bundle to Central Portal..."
    sbt sonatypeCentralUpload

    echo "==> Done! Check https://central.sonatype.com for deployment status."
}

# Main dispatcher
case "${1:-help}" in
    build)
        cmd_build
        ;;
    test)
        shift  # Remove "test" from arguments
        cmd_test "$@"  # Pass all remaining arguments
        ;;
    publish)
        cmd_publish
        ;;
    help|--help|-h)
        show_help
        ;;
    *)
        echo "Unknown command: $1"
        echo ""
        show_help
        exit 1
        ;;
esac
