(ns pc4.araf.qr
  (:import
    [java.io ByteArrayOutputStream]
    [com.google.zxing BarcodeFormat EncodeHintType]
    [com.google.zxing.client.j2se MatrixToImageWriter]
    [com.google.zxing.qrcode QRCodeWriter]))

(defn generate
  "Generates a QR code for the given base URL, access key, and NHS number.
   Returns a byte array of the PNG image.
   Options:
   - :size - QR code size in pixels (default 250)"
  ([base-url access-key nhs-number]
   (generate base-url access-key nhs-number {}))
  ([base-url access-key nhs-number {:keys [size] :or {size 250}}]
   (let [url (str base-url "/" access-key "/" nhs-number)
         writer (QRCodeWriter.)
         bit-matrix (.encode writer url BarcodeFormat/QR_CODE size size)
         output-stream (ByteArrayOutputStream.)]
     (MatrixToImageWriter/writeToStream bit-matrix "PNG" output-stream)
     (.toByteArray output-stream))))