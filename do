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
