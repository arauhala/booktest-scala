package booktest.examples

import booktest._
import java.io.File
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * Tests for image output functionality.
 * Demonstrates timage() and iimage() for including images in test output.
 */
class ImageTest extends TestSuite {

  /** Test basic image output */
  def testBasicImage(t: TestCaseRun): Unit = {
    t.h1("Basic Image Output")

    // Create a simple test image
    val image = createTestImage(100, 100, java.awt.Color.BLUE)
    val imageFile = t.file("test_image.png")
    ImageIO.write(image, "png", imageFile)

    t.tln("Generated test image:")
    t.timage(imageFile, "Blue test image")
    t.tln("Image included in output")
  }

  /** Test info image (not in snapshot) */
  def testInfoImage(t: TestCaseRun): Unit = {
    t.h1("Info Image Output")

    // Create a diagnostic image
    val image = createTestImage(50, 50, java.awt.Color.RED)
    val imageFile = t.file("diagnostic.png")
    ImageIO.write(image, "png", imageFile)

    t.tln("Main test content")
    t.iimage(imageFile, "Diagnostic image (info only)")
    t.tln("Diagnostic image not in snapshot")
  }

  /** Test file hash renaming */
  def testFileHashRename(t: TestCaseRun): Unit = {
    t.h1("File Hash Renaming")

    // Create a file
    val testFile = t.file("output.txt")
    val writer = new java.io.PrintWriter(testFile)
    writer.println("Test content for hashing")
    writer.close()

    // Rename to hash
    val hashedName = t.renameFileToHash("output.txt")
    t.tln(s"Original: output.txt")
    t.tln(s"Renamed to: $hashedName")
  }

  /** Test multiple images */
  def testMultipleImages(t: TestCaseRun): Unit = {
    t.h1("Multiple Images")

    val colors = Seq(
      ("red", java.awt.Color.RED),
      ("green", java.awt.Color.GREEN),
      ("blue", java.awt.Color.BLUE)
    )

    colors.foreach { case (name, color) =>
      val image = createTestImage(30, 30, color)
      val imageFile = t.file(s"$name.png")
      ImageIO.write(image, "png", imageFile)
      t.timage(imageFile, s"$name square")
    }

    t.tln("All images generated")
  }

  /** Create a simple solid color test image */
  private def createTestImage(width: Int, height: Int, color: java.awt.Color): BufferedImage = {
    val image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.setColor(color)
    g.fillRect(0, 0, width, height)
    g.dispose()
    image
  }
}
