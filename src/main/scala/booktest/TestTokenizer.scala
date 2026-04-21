package booktest

/** Tokenizer that breaks text into tokens matching Python booktest's TestTokenizer.
  *
  * Token types:
  * - Whitespace sequences (spaces/tabs) → single token
  * - Numbers: optional sign, digits, optional decimal (e.g., "123", "-3.14", "+42")
  * - Alphanumeric words (consecutive letters/digits/underscores)
  * - Single special characters (each is its own token)
  */
class TestTokenizer(buf: String, private var at: Int = 0) extends Iterator[String] {

  def hasNext: Boolean = at < buf.length

  def next(): String = {
    if (!hasNext) throw new NoSuchElementException("No more tokens")

    val ch = buf.charAt(at)

    // Whitespace sequences (spaces and tabs only, not newlines)
    if (ch == ' ' || ch == '\t') {
      val start = at
      while (at < buf.length && (buf.charAt(at) == ' ' || buf.charAt(at) == '\t')) {
        at += 1
      }
      return buf.substring(start, at)
    }

    // Newline is a single token
    if (ch == '\n') {
      at += 1
      return "\n"
    }

    // Numbers: optional sign followed by digits with optional decimal
    // Note: '.' does NOT start a number — it's only a decimal separator within a number.
    // This ensures ".40" tokenizes as "." + "40", not ".40", which is critical for
    // token alignment between snapshot (tokenized as one string) and output (tokenized
    // across multiple feed calls like testFeed("..") + infoFeed("40ms")).
    if (ch == '+' || ch == '-' || ch.isDigit) {
      val start = at
      // Optional sign
      if ((ch == '+' || ch == '-') && at + 1 < buf.length && buf.charAt(at + 1).isDigit) {
        at += 1
      } else if (!ch.isDigit) {
        // Sign not followed by digit/dot — treat as special char
        at += 1
        return buf.substring(start, at)
      }
      // Integer part
      while (at < buf.length && buf.charAt(at).isDigit) {
        at += 1
      }
      // Decimal part
      if (at < buf.length && buf.charAt(at) == '.' && at + 1 < buf.length && buf.charAt(at + 1).isDigit) {
        at += 1 // skip dot
        while (at < buf.length && buf.charAt(at).isDigit) {
          at += 1
        }
      }
      // Scientific notation
      if (at < buf.length && (buf.charAt(at) == 'e' || buf.charAt(at) == 'E')) {
        val savedAt = at
        at += 1
        if (at < buf.length && (buf.charAt(at) == '+' || buf.charAt(at) == '-')) {
          at += 1
        }
        if (at < buf.length && buf.charAt(at).isDigit) {
          while (at < buf.length && buf.charAt(at).isDigit) {
            at += 1
          }
        } else {
          at = savedAt // not scientific notation, backtrack
        }
      }
      val token = buf.substring(start, at)
      if (token.length > 0 && (token != "+" && token != "-" && token != ".")) {
        return token
      }
      // Fallthrough: single char
      return token
    }

    // Alphanumeric words (letters, digits, underscores)
    if (ch.isLetterOrDigit || ch == '_') {
      val start = at
      while (at < buf.length && (buf.charAt(at).isLetterOrDigit || buf.charAt(at) == '_')) {
        at += 1
      }
      return buf.substring(start, at)
    }

    // Single special character
    at += 1
    buf.substring(at - 1, at)
  }
}

/** Iterator wrapper that supports lookahead via head property.
  * Matches Python booktest's BufferIterator.
  */
class BufferIterator[T](iter: Iterator[T]) extends Iterator[T] {
  private var _head: Option[T] = if (iter.hasNext) Some(iter.next()) else None

  /** Peek at next element without consuming */
  def head: Option[T] = _head

  def hasNext: Boolean = _head.isDefined

  def next(): T = {
    val current = _head.getOrElse(throw new NoSuchElementException("No more elements"))
    _head = if (iter.hasNext) Some(iter.next()) else None
    current
  }
}

object TestTokenizer {
  /** Tokenize a string into a list of tokens */
  def tokenize(text: String): List[String] = {
    new TestTokenizer(text).toList
  }
}
