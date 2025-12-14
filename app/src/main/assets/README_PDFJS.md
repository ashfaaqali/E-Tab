# PDF.js Setup Instructions

To enable the PDF viewer features (Text Selection, Definition, Highlighting), you need to add the Mozilla PDF.js library to your assets.

1.  **Download PDF.js**:
    *   Go to [https://github.com/mozilla/pdf.js/releases](https://github.com/mozilla/pdf.js/releases).
    *   Download the latest `pdfjs-*-dist.zip` (e.g., `pdfjs-3.11.174-dist.zip`).

2.  **Extract and Copy**:
    *   Unzip the downloaded file.
    *   You will see `build/` and `web/` folders.
    *   Copy these two folders into your Android project at:
        `app/src/main/assets/pdfjs/`

    Your structure should look like this:
    ```
    app/src/main/assets/
        pdfjs/
            build/
                pdf.js
                pdf.worker.js
            web/
                viewer.html
                viewer.css
                viewer.js
                ...
    ```

3.  **Build and Run**:
    *   Once the files are in place, the `PdfReaderActivity` will automatically load the viewer.
