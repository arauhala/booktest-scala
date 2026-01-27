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
    echo "  ./do test ExampleTests"
    echo "  ./do publish"
}

cmd_build() {
    sbt compile
}

cmd_test() {
    local suite="${1:-booktest.examples.ExampleTests}"
    # Add package prefix if not already present
    if [[ "$suite" != booktest.* ]]; then
        suite="booktest.examples.$suite"
    fi
    sbt "Test/runMain booktest.BooktestMain -v $suite"
}

cmd_publish() {
    echo "==> Setting up GPG environment..."
    export GPG_TTY=$(tty)

    echo "==> Publishing signed artifacts to Sonatype..."
    sbt publishSigned

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
