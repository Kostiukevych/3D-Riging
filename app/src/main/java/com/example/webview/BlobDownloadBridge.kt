package com.example.webview

import android.webkit.JavascriptInterface

class BlobDownloadBridge(
  private val onBase64Received: (base64: String, fileName: String, mimeType: String) -> Unit,
  private val onError: (String) -> Unit,
) {

  @JavascriptInterface
  fun saveBase64File(base64Data: String, fileName: String, mimeType: String) {
    onBase64Received(base64Data, fileName, mimeType)
  }

  @JavascriptInterface
  fun onDownloadError(message: String) {
    onError(message)
  }

  companion object {
    const val INTERFACE_NAME = "AndroidBlobBridge"

    val INJECT_SCRIPT =
      """
      (function() {
        if (window.__m2mBlobHookInstalled) return;
        window.__m2mBlobHookInstalled = true;

        var blobMimeCache = new Map();
        var origCreateObjectURL = URL.createObjectURL;
        URL.createObjectURL = function(obj) {
          var url = origCreateObjectURL.call(URL, obj);
          try {
            if (obj && obj.type) {
              blobMimeCache.set(url, obj.type);
            }
          } catch (e) {}
          return url;
        };

        window.__m2mFetchBlobToAndroid = function(blobUrl, suggestedName) {
          if (!blobUrl) return;
          if (blobUrl.indexOf('data:') === 0) {
            try {
              var parts = blobUrl.split(',');
              var header = parts[0] || '';
              var mimeMatch = header.match(/data:([^;]+)/);
              var mime = mimeMatch ? mimeMatch[1] : 'application/octet-stream';
              var b64 = parts[1] || '';
              window.$INTERFACE_NAME.saveBase64File(b64, suggestedName || 'mesh2motion_export.glb', mime);
            } catch (err) {
              window.$INTERFACE_NAME.onDownloadError(String(err));
            }
            return;
          }

          fetch(blobUrl)
            .then(function(response) {
              if (!response.ok) throw new Error('HTTP ' + response.status);
              return response.blob();
            })
            .then(function(blob) {
              var mime = blob.type || blobMimeCache.get(blobUrl) || 'application/octet-stream';
              var reader = new FileReader();
              reader.onloadend = function() {
                var result = reader.result || '';
                var commaIndex = result.indexOf(',');
                var base64 = commaIndex >= 0 ? result.substring(commaIndex + 1) : result;
                window.$INTERFACE_NAME.saveBase64File(
                  base64,
                  suggestedName || 'mesh2motion_export.glb',
                  mime
                );
              };
              reader.onerror = function() {
                window.$INTERFACE_NAME.onDownloadError('FileReader failed for blob export');
              };
              reader.readAsDataURL(blob);
            })
            .catch(function(err) {
              window.$INTERFACE_NAME.onDownloadError(String(err));
            });
        };

        function handlePossibleDownloadLink(el) {
          if (!el || el.tagName !== 'A') return false;
          var href = el.getAttribute('href') || el.href || '';
          if (href.indexOf('blob:') === 0 || href.indexOf('data:') === 0) {
            var filename = el.getAttribute('download') || 'mesh2motion_export.glb';
            window.__m2mFetchBlobToAndroid(href, filename);
            return true;
          }
          return false;
        }

        var origAnchorClick = HTMLAnchorElement.prototype.click;
        HTMLAnchorElement.prototype.click = function() {
          if (handlePossibleDownloadLink(this)) return;
          return origAnchorClick.apply(this, arguments);
        };

        var origDispatchEvent = EventTarget.prototype.dispatchEvent;
        EventTarget.prototype.dispatchEvent = function(event) {
          if (event && event.type === 'click' && this.tagName === 'A') {
            if (handlePossibleDownloadLink(this)) return false;
          }
          return origDispatchEvent.apply(this, arguments);
        };

        document.addEventListener('click', function(event) {
          var target = event.target;
          while (target && target.tagName !== 'A') {
            target = target.parentElement;
          }
          if (!target) return;
          var href = target.getAttribute('href') || target.href || '';
          if (href.indexOf('blob:') === 0 || href.indexOf('data:') === 0) {
            event.preventDefault();
            event.stopPropagation();
            var filename = target.getAttribute('download') || 'mesh2motion_export.glb';
            window.__m2mFetchBlobToAndroid(href, filename);
          }
        }, true);
      })();
      """
        .trimIndent()
  }
}
