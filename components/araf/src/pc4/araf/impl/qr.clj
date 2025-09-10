(ns pc4.araf.impl.qr
  (:import
    (java.io ByteArrayOutputStream)
    (com.google.zxing BarcodeFormat EncodeHintType)
    (com.google.zxing.client.j2se MatrixToImageWriter)
    (com.google.zxing.qrcode QRCodeWriter)))

(defn generate
  "Generates a QR code for the given url
   Returns a byte array of the PNG image.
   Options:
   - :size - QR code size in pixels (default 250)"
  ([url]
   (generate url {}))
  ([url {:keys [size] :or {size 250}}]
   (let [writer (QRCodeWriter.)
         bit-matrix (.encode writer url BarcodeFormat/QR_CODE size size)
         output-stream (ByteArrayOutputStream.)]
     (MatrixToImageWriter/writeToStream bit-matrix "PNG" output-stream)
     (.toByteArray output-stream))))