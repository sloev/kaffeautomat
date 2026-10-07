import qrcode from 'qrcode-generator';

/** QR code as an SVG string (used for the pairing code shown in the dashboard). */
export function qrSvg(text) {
  const qr = qrcode(0, 'M');
  qr.addData(text);
  qr.make();
  return qr.createSvgTag({ cellSize: 6, margin: 4, scalable: true });
}
