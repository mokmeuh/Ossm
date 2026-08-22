import { inflateSync } from "node:zlib";

const PNG_SIGNATURE = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);

export function decodePng(buffer) {
  assertPngSignature(buffer);

  let width = 0;
  let height = 0;
  let bitDepth = 0;
  let colorType = 0;
  const idatParts = [];

  let offset = PNG_SIGNATURE.length;
  while (offset < buffer.length) {
    const length = buffer.readUInt32BE(offset);
    offset += 4;
    const chunkType = buffer.toString("ascii", offset, offset + 4);
    offset += 4;
    const chunkData = buffer.subarray(offset, offset + length);
    offset += length + 4;

    if (chunkType === "IHDR") {
      width = chunkData.readUInt32BE(0);
      height = chunkData.readUInt32BE(4);
      bitDepth = chunkData[8];
      colorType = chunkData[9];
      continue;
    }
    if (chunkType === "IDAT") {
      idatParts.push(chunkData);
      continue;
    }
    if (chunkType === "IEND") {
      break;
    }
  }

  if (width <= 0 || height <= 0) {
    throw new Error("Invalid PNG dimensions.");
  }
  if (bitDepth !== 8) {
    throw new Error(`Unsupported PNG bit depth: ${bitDepth}`);
  }

  const channels = channelsForColorType(colorType);
  const bytesPerPixel = channels;
  const stride = width * bytesPerPixel;
  const inflated = inflateSync(Buffer.concat(idatParts));
  const rgba = Buffer.alloc(width * height * 4);

  let inputOffset = 0;
  let outputOffset = 0;
  let previousRow = Buffer.alloc(stride);
  for (let row = 0; row < height; row += 1) {
    const filter = inflated[inputOffset];
    inputOffset += 1;
    const currentRow = inflated.subarray(inputOffset, inputOffset + stride);
    inputOffset += stride;
    const unfiltered = unfilterRow(filter, currentRow, previousRow, bytesPerPixel);
    outputOffset = writeRgba(unfiltered, colorType, rgba, outputOffset);
    previousRow = Buffer.from(unfiltered);
  }

  return {
    width,
    height,
    rgba,
    gray: toGray(rgba),
  };
}

function assertPngSignature(buffer) {
  if (!buffer.subarray(0, PNG_SIGNATURE.length).equals(PNG_SIGNATURE)) {
    throw new Error("Invalid PNG signature.");
  }
}

function channelsForColorType(colorType) {
  if (colorType === 0) return 1;
  if (colorType === 2) return 3;
  if (colorType === 4) return 2;
  if (colorType === 6) return 4;
  throw new Error(`Unsupported PNG color type: ${colorType}`);
}

function unfilterRow(filter, current, previous, bytesPerPixel) {
  const output = Buffer.alloc(current.length);
  if (filter === 0) {
    current.copy(output);
    return output;
  }
  if (filter === 1) {
    for (let index = 0; index < current.length; index += 1) {
      const left = index >= bytesPerPixel ? output[index - bytesPerPixel] : 0;
      output[index] = (current[index] + left) & 0xff;
    }
    return output;
  }
  if (filter === 2) {
    for (let index = 0; index < current.length; index += 1) {
      output[index] = (current[index] + previous[index]) & 0xff;
    }
    return output;
  }
  if (filter === 3) {
    for (let index = 0; index < current.length; index += 1) {
      const left = index >= bytesPerPixel ? output[index - bytesPerPixel] : 0;
      const up = previous[index];
      output[index] = (current[index] + Math.floor((left + up) / 2)) & 0xff;
    }
    return output;
  }
  if (filter === 4) {
    for (let index = 0; index < current.length; index += 1) {
      const left = index >= bytesPerPixel ? output[index - bytesPerPixel] : 0;
      const up = previous[index];
      const upLeft = index >= bytesPerPixel ? previous[index - bytesPerPixel] : 0;
      output[index] = (current[index] + paethPredictor(left, up, upLeft)) & 0xff;
    }
    return output;
  }
  throw new Error(`Unsupported PNG filter: ${filter}`);
}

function writeRgba(row, colorType, rgba, outputOffset) {
  if (colorType === 6) {
    row.copy(rgba, outputOffset);
    return outputOffset + row.length;
  }
  if (colorType === 2) {
    for (let index = 0; index < row.length; index += 3) {
      rgba[outputOffset] = row[index];
      rgba[outputOffset + 1] = row[index + 1];
      rgba[outputOffset + 2] = row[index + 2];
      rgba[outputOffset + 3] = 255;
      outputOffset += 4;
    }
    return outputOffset;
  }
  if (colorType === 0) {
    for (let index = 0; index < row.length; index += 1) {
      const value = row[index];
      rgba[outputOffset] = value;
      rgba[outputOffset + 1] = value;
      rgba[outputOffset + 2] = value;
      rgba[outputOffset + 3] = 255;
      outputOffset += 4;
    }
    return outputOffset;
  }
  if (colorType === 4) {
    for (let index = 0; index < row.length; index += 2) {
      const value = row[index];
      rgba[outputOffset] = value;
      rgba[outputOffset + 1] = value;
      rgba[outputOffset + 2] = value;
      rgba[outputOffset + 3] = row[index + 1];
      outputOffset += 4;
    }
    return outputOffset;
  }
  throw new Error(`Unsupported PNG color type: ${colorType}`);
}

function toGray(rgba) {
  const gray = new Uint8Array(rgba.length / 4);
  for (let index = 0, pixel = 0; index < rgba.length; index += 4, pixel += 1) {
    gray[pixel] = (rgba[index] * 77 + rgba[index + 1] * 150 + rgba[index + 2] * 29) >> 8;
  }
  return gray;
}

function paethPredictor(left, up, upLeft) {
  const predictor = left + up - upLeft;
  const distanceLeft = Math.abs(predictor - left);
  const distanceUp = Math.abs(predictor - up);
  const distanceUpLeft = Math.abs(predictor - upLeft);
  if (distanceLeft <= distanceUp && distanceLeft <= distanceUpLeft) {
    return left;
  }
  if (distanceUp <= distanceUpLeft) {
    return up;
  }
  return upLeft;
}
