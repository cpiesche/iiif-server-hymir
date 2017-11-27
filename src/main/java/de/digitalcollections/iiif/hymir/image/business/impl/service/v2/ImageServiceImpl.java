package de.digitalcollections.iiif.hymir.image.business.impl.service.v2;

import com.google.common.collect.Streams;
import de.digitalcollections.core.business.api.ResourceService;
import de.digitalcollections.core.model.api.MimeType;
import de.digitalcollections.core.model.api.resource.Resource;
import de.digitalcollections.core.model.api.resource.enums.ResourcePersistenceType;
import de.digitalcollections.core.model.api.resource.exceptions.ResourceIOException;
import de.digitalcollections.iiif.hymir.image.business.api.service.ImageSecurityService;
import de.digitalcollections.iiif.hymir.image.business.api.service.v2.ImageService;
import de.digitalcollections.iiif.hymir.model.api.exception.InvalidParametersException;
import de.digitalcollections.iiif.hymir.model.api.exception.ResourceNotFoundException;
import de.digitalcollections.iiif.hymir.model.api.exception.UnsupportedFormatException;
import de.digitalcollections.iiif.model.image.ImageApiProfile;
import de.digitalcollections.iiif.model.image.ImageApiSelector;
import de.digitalcollections.iiif.model.image.Size;
import de.digitalcollections.iiif.model.image.TileInfo;
import de.digitalcollections.turbojpeg.imageio.TurboJpegImageReadParam;
import de.digitalcollections.turbojpeg.imageio.TurboJpegImageReader;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.imgscalr.Scalr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ImageServiceImpl implements ImageService {
  private static final Logger LOGGER = LoggerFactory.getLogger(ImageServiceImpl.class);

  @Autowired(required = false)
  private ImageSecurityService imageSecurityService;

  @Autowired
  private ResourceService resourceService;

  private class DecodedImage {
    public BufferedImage img;
    public Dimension targetSize;
    public int rotation;

    // Small value type to hold information about decoding results
    public DecodedImage(BufferedImage img, Dimension targetSize, int rotation) {
      /** Decoded image **/
      this.img = img;

      /** Final target size for scaling **/
      this.targetSize = targetSize;

      /** Rotation needed after decoding? **/
      this.rotation = rotation;
    }
  }

  /** Update ImageService based on the image **/
  private void enrichInfo(ImageReader reader, de.digitalcollections.iiif.model.image.ImageService info) throws IOException {
    ImageApiProfile profile = new ImageApiProfile();
    profile.addFeature(
        ImageApiProfile.Feature.BASE_URI_REDIRECT,
        ImageApiProfile.Feature.CORS,
        ImageApiProfile.Feature.JSONLD_MEDIA_TYPE,
        ImageApiProfile.Feature.MIRRORING,
        ImageApiProfile.Feature.PROFILE_LINK_HEADER,
        ImageApiProfile.Feature.REGION_BY_PCT,
        ImageApiProfile.Feature.REGION_BY_PX,
        ImageApiProfile.Feature.REGION_SQUARE,
        ImageApiProfile.Feature.ROTATION_BY_90S,
        ImageApiProfile.Feature.SIZE_BY_CONFINED_WH,
        ImageApiProfile.Feature.SIZE_BY_H,
        ImageApiProfile.Feature.SIZE_BY_PCT,
        ImageApiProfile.Feature.SIZE_BY_W,
        ImageApiProfile.Feature.SIZE_BY_WH);
    info.addProfile(ImageApiProfile.LEVEL_TWO, profile);

    info.setWidth(reader.getWidth(0));
    info.setHeight(reader.getHeight(0));

    // Check if multiple resolutions are supported
    int numImages = reader.getNumImages(true);
    if (numImages > 1) {
      for (int i=0; i < numImages; i++) {
        info.addSize(new Size(reader.getWidth(i), reader.getHeight(i)));
      }
    }

    // Check if tiling is supported
    if (reader.isImageTiled(0)) {
      int width = reader.getTileWidth(0);
      TileInfo tileInfo = new TileInfo(width);
      for (int i=0; i < numImages; i++) {
        int scaledWidth = reader.getTileWidth(i);
        tileInfo.addScaleFactor(width / scaledWidth);
      }
      info.addTile(tileInfo);
    } else if (reader instanceof TurboJpegImageReader) {
      // Cropping aligned to MCUs is faster, and MCUs are either 4, 8 or 16 pixels, so if we stick to multiples
      // of 16 for width/height, we are safe.
      if (reader.getWidth(0) >= 512 && reader.getHeight(0) >= 512) {
        TileInfo ti = new TileInfo(512);
        // Scale factors for JPEGs are not always integral, so we hardcode them
        ti.addScaleFactor(1, 2, 4, 8, 16);
        info.addTile(ti);
      }
      if (reader.getWidth(0) >= 1024 && reader.getHeight(0) >= 1024) {
        TileInfo ti = new TileInfo(1024);
        ti.addScaleFactor(1, 2, 4, 8, 16);
        info.addTile(ti);
      }
    }
  }

  /** Try to obtain a {@link ImageReader} for a given identifier **/
  private ImageReader getReader(String identifier) throws ResourceNotFoundException, UnsupportedFormatException, IOException {
    if (imageSecurityService != null && !imageSecurityService.isAccessAllowed(identifier)) {
      throw new ResourceNotFoundException();
    }
    Resource res = null;
    try {
      res = resourceService.get(identifier, ResourcePersistenceType.RESOLVED, MimeType.MIME_IMAGE);
    } catch (ResourceIOException e) {
      throw new ResourceNotFoundException();
    }
    ImageInputStream iis = ImageIO.createImageInputStream(resourceService.getInputStream(res));
    ImageReader reader = Streams.stream(ImageIO.getImageReaders(iis))
        .findFirst()
        .orElseThrow(() -> new UnsupportedFormatException());
    reader.setInput(iis);
    return reader;
  }

  @Override
  public void readImageInfo(String identifier, de.digitalcollections.iiif.model.image.ImageService info)
      throws UnsupportedFormatException, UnsupportedOperationException, ResourceNotFoundException, IOException {
    enrichInfo(getReader(identifier), info);
  }

  /** Determine parameters for image reading based on the IIIF selector and a given scaling factor **/
  private ImageReadParam getReadParam(ImageReader reader, ImageApiSelector selector, double decodeScaleFactor) throws IOException {
    ImageReadParam readParam = reader.getDefaultReadParam();
    Dimension nativeDimensions = new Dimension(reader.getWidth(0), reader.getHeight(0));
    Rectangle targetRegion = selector.getRegion().resolve(nativeDimensions);
    // IIIF regions are always relative to the native size, while ImageIO regions are always relative to the decoded
    // image size, hence the conversion
    Rectangle decodeRegion = new Rectangle(
        (int) Math.ceil(targetRegion.getX() * decodeScaleFactor),
        (int) Math.ceil(targetRegion.getY() * decodeScaleFactor),
        (int) Math.ceil(targetRegion.getWidth() * decodeScaleFactor),
        (int) Math.ceil(targetRegion.getHeight() * decodeScaleFactor));
    readParam.setSourceRegion(decodeRegion);

    // TurboJpegImageReader can rotate during decoding
    boolean didRotate = false;
    if (selector.getRotation().getRotation() != 0 && reader instanceof TurboJpegImageReader) {
      ((TurboJpegImageReadParam) readParam).setRotationDegree((int) selector.getRotation().getRotation());
      didRotate = true;
    }
    return readParam;
  }

  /** Decode an image **/
  private DecodedImage readImage(String identifier, ImageApiSelector selector) throws IOException, ResourceNotFoundException, UnsupportedFormatException {
    ImageReader reader = getReader(identifier);

    // TODO: Special case JPEG reader && output format JPEG

    if ((selector.getRotation().getRotation() % 90) != 0) {
      throw new UnsupportedOperationException("Can only rotate by multiples of 90 degrees.");
    }

    Dimension nativeDimensions = new Dimension(reader.getWidth(0), reader.getHeight(0));
    Rectangle targetRegion = selector.getRegion().resolve(nativeDimensions);
    Dimension croppedDimensions = new Dimension(targetRegion.width, targetRegion.height);
    Dimension targetSize = selector.getSize().resolve(croppedDimensions, ImageApiProfile.LEVEL_TWO);

    // Determine the closest resolution to the target that can be decoded directly
    double targetScaleFactor = (double) targetSize.width / targetRegion.getWidth();
    double decodeScaleFactor = 1.0;
    int imageIndex = 0;
    for (int idx = 0; idx < reader.getNumImages(true); idx++) {
      double factor = (double) reader.getWidth(idx) / nativeDimensions.width;
      double currentError = Math.abs(targetScaleFactor - factor);
      double bestError = Math.abs(targetScaleFactor - decodeScaleFactor);
      if (Math.abs(targetScaleFactor - factor) < Math.abs(targetScaleFactor - decodeScaleFactor)) {
        decodeScaleFactor = factor;
        imageIndex = idx;
      }
    }
    Dimension decodeSize = new Dimension(
        (int) (targetRegion.getWidth() * decodeScaleFactor),
        (int) (targetRegion.getHeight() * decodeScaleFactor));
    ImageReadParam readParam = getReadParam(reader, selector, decodeScaleFactor);
    int rotation = (int) selector.getRotation().getRotation();
    if (readParam instanceof TurboJpegImageReadParam && ((TurboJpegImageReadParam) readParam).getRotationDegree() != 0) {
      if (rotation == 90 || rotation == 270) {
        int w = targetSize.width;
        int h = targetSize.height;
        targetSize.width = h;
        targetSize.height = w;
      }
      rotation = 0;
    }
    try {
      return new DecodedImage(reader.read(imageIndex, readParam), targetSize, rotation);
    } catch (IllegalArgumentException e) {
      throw new UnsupportedOperationException(e);
    }
  }

  /** Apply transformations to an decoded image **/
  private BufferedImage transformImage(BufferedImage inputImage, Dimension targetSize, int rotation, boolean mirror,
                                       ImageApiProfile.Quality quality) {
    BufferedImage img = inputImage;
    int inType = img.getType();
    boolean needsAdditionalScaling = !new Dimension(img.getWidth(), img.getHeight()).equals(targetSize);
    if (needsAdditionalScaling) {
      img = Scalr.resize(img, Scalr.Method.BALANCED, Scalr.Mode.FIT_EXACT, targetSize.width, targetSize.height);
    }

    if (rotation != 0) {
      Scalr.Rotation rot = null;
      switch (rotation) {
        case 90:
          rot = Scalr.Rotation.CW_90;
          break;
        case 180:
          rot = Scalr.Rotation.CW_180;
          break;
        case 270:
          rot = Scalr.Rotation.CW_270;
          break;
      }
      img = Scalr.rotate(img, rot);
    }
    if (mirror) {
      img = Scalr.rotate(img, Scalr.Rotation.FLIP_HORZ);
    }
    // Quality
    int outType = -1;
    switch (quality) {
      case GRAY:
        outType = BufferedImage.TYPE_BYTE_GRAY;
        break;
      case BITONAL:
        outType = BufferedImage.TYPE_BYTE_BINARY;
        break;
      case COLOR:
        outType = BufferedImage.TYPE_3BYTE_BGR;
        break;
      default:
        outType = inType;
    }
    if (outType != img.getType()) {
      BufferedImage newImg = new BufferedImage(img.getWidth(), img.getHeight(), outType);
      Graphics2D g2d = newImg.createGraphics();
      g2d.drawImage(img, 0, 0, null);
      img = newImg;
      g2d.dispose();
    }
    return img;
  }

  @Override
  public void processImage(String identifier, ImageApiSelector selector, OutputStream os)
      throws InvalidParametersException, UnsupportedOperationException, UnsupportedFormatException, ResourceNotFoundException, IOException {
    DecodedImage img;
    try {
      img = readImage(identifier, selector);
    } catch (IllegalArgumentException e) {
      throw new InvalidParametersException();
    }
    BufferedImage outImg = transformImage(img.img, img.targetSize, img.rotation, selector.getRotation().isMirror(), selector.getQuality());

    ImageWriter writer = Streams.stream(ImageIO.getImageWriters(new ImageTypeSpecifier(outImg), selector.getFormat().name()))
        .findFirst()
        .orElseThrow(() -> new UnsupportedFormatException());
    ImageOutputStream ios = ImageIO.createImageOutputStream(os);
    writer.setOutput(ios);
    writer.write(null, new IIOImage(outImg, null, null), null);
    writer.dispose();
    ios.flush();
  }
}
