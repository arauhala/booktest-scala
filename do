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
    local suite="${1:-}"
    if [[ -z "$suite" ]]; then
        # No args - use default group from booktest.conf
        sbt "Test/runMain booktest.BooktestMain"
    elif [[ "$suite" != booktest.* ]] && [[ "$suite" != *"."* ]]; then
        # No dots - might be a group name, let BooktestMain handle it
        sbt "Test/runMain booktest.BooktestMain $suite"
    else
        # Full class name or needs package prefix
        if [[ "$suite" != booktest.* ]]; then
            suite="booktest.examples.$suite"
        fi
        sbt "Test/runMain booktest.BooktestMain $suite"
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
        cmd_test "$2"
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
