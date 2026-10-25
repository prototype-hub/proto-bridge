(() => {
  "use strict";
  if (window.__protoBridgeInspector) return;
  window.__protoBridgeInspector = true;
  // 沙箱文档使用不透明来源；只向父级传递检查结果，平台来源由实际加载的脚本地址确定。
  const platformOrigin = new URL(document.currentScript.src).origin;
  let enabled = false;
  let selected;
  const marker = document.createElement("div");
  marker.setAttribute("aria-hidden", "true");
  marker.style.cssText =
    "position:fixed;pointer-events:none;border:2px solid #1677ff;background:#1677ff14;z-index:2147483647;display:none;box-sizing:border-box";
  document.documentElement.appendChild(marker);
  const relay = (value) =>
    document.querySelectorAll("iframe").forEach((frame) => {
      try {
        frame.contentWindow.postMessage(value, "*");
      } catch (_) {}
    });
  const draw = (element) => {
    selected = element;
    if (!enabled || !element) {
      marker.style.display = "none";
      return;
    }
    const r = element.getBoundingClientRect();
    Object.assign(marker.style, {
      display: "block",
      left: r.left + "px",
      top: r.top + "px",
      width: r.width + "px",
      height: r.height + "px",
    });
  };

  // Geometry belongs only to this root document. It neither reads child DOM nor receives identities.
  if (parent === top && window !== top) {
    let command,
      active = false,
      capture,
      cursor,
      first,
      snapshot,
      monitor;
    const surface = document.createElement("div");
    surface.dataset.reviewAnchorSurface = "true";
    surface.tabIndex = 0;
    surface.setAttribute("role", "application");
    surface.setAttribute("aria-label", "Page position drawing / 页面位置绘制");
    surface.style.cssText =
      "position:fixed;inset:0;overflow:hidden;z-index:2147483647;display:none;outline:2px solid #1677ff;touch-action:none";
    const shape = document.createElement("div");
    shape.dataset.reviewAnchorMarker = "true";
    shape.style.cssText =
      "position:absolute;pointer-events:none;border:2px solid #1677ff;background:#1677ff26;box-sizing:border-box";
    surface.appendChild(shape);
    document.documentElement.appendChild(surface);
    const dimensions = () => ({
      documentWidth: Math.round(
        Math.max(
          document.documentElement.scrollWidth,
          document.documentElement.clientWidth,
          document.body?.scrollWidth || 0,
        ),
      ),
      documentHeight: Math.round(
        Math.max(
          document.documentElement.scrollHeight,
          document.documentElement.clientHeight,
          document.body?.scrollHeight || 0,
        ),
      ),
      viewportWidth: Math.round(innerWidth),
      viewportHeight: Math.round(innerHeight),
    });
    const validAnchor = (a) => {
      if (
        !a ||
        typeof a !== "object" ||
        Object.keys(a).length !== 10 ||
        a.space !== "root-document-css-px-v1" ||
        !["point", "rect", "ellipse"].includes(a.shape)
      )
        return false;
      if (
        ![
          a.x,
          a.y,
          a.width,
          a.height,
          a.documentWidth,
          a.documentHeight,
          a.viewportWidth,
          a.viewportHeight,
        ].every(Number.isInteger)
      )
        return false;
      return (
        a.documentWidth >= 1 &&
        a.documentWidth <= 1000000 &&
        a.documentHeight >= 1 &&
        a.documentHeight <= 1000000 &&
        a.viewportWidth >= 1 &&
        a.viewportWidth <= 16384 &&
        a.viewportHeight >= 1 &&
        a.viewportHeight <= 16384 &&
        a.x >= 0 &&
        a.y >= 0 &&
        a.width >= 0 &&
        a.height >= 0 &&
        a.x + a.width <= a.documentWidth &&
        a.y + a.height <= a.documentHeight &&
        (a.shape === "point"
          ? a.width === 0 && a.height === 0
          : a.width >= 1 && a.height >= 1)
      );
    };
    const located = () =>
      command &&
      !location.search &&
      !location.hash &&
      location.pathname.slice(command.prefixLength) === command.resourcePath;
    const sameDimensions = (a) => {
      const now = dimensions();
      return [
        "documentWidth",
        "documentHeight",
        "viewportWidth",
        "viewportHeight",
      ].every((key) => a[key] === now[key]);
    };
    const send = (status, anchor) => {
      if (command)
        parent.postMessage(
          {
            type: "PROTOTYPE_REVIEW_ANCHOR_EVENT",
            channel: command.channel,
            generation: command.generation,
            status,
            ...(anchor ? { anchor } : {}),
          },
          platformOrigin,
        );
    };
    const hide = () => {
      active = false;
      capture = undefined;
      first = undefined;
      snapshot = undefined;
      surface.style.display = "none";
      clearInterval(monitor);
    };
    const unavailable = () => {
      hide();
      send("unavailable");
    };
    const paint = (a) => {
      Object.assign(shape.style, {
        left: a.x - scrollX - (a.shape === "point" ? 6 : 0) + "px",
        top: a.y - scrollY - (a.shape === "point" ? 6 : 0) + "px",
        width: (a.shape === "point" ? 12 : a.width) + "px",
        height: (a.shape === "point" ? 12 : a.height) + "px",
        borderRadius: a.shape === "rect" ? "0" : "50%",
      });
    };
    const position = (event) => ({
      x: Math.max(
        0,
        Math.min(snapshot.documentWidth, Math.round(event.clientX + scrollX)),
      ),
      y: Math.max(
        0,
        Math.min(snapshot.documentHeight, Math.round(event.clientY + scrollY)),
      ),
    });
    const geometry = (point) => ({
      space: "root-document-css-px-v1",
      shape: command.shape,
      x: first ? Math.min(first.x, point.x) : point.x,
      y: first ? Math.min(first.y, point.y) : point.y,
      width:
        command.shape === "point"
          ? 0
          : Math.abs(point.x - (first?.x ?? point.x)),
      height:
        command.shape === "point"
          ? 0
          : Math.abs(point.y - (first?.y ?? point.y)),
      ...snapshot,
    });
    const check = () => {
      if (active && (!located() || !sameDimensions(snapshot))) {
        unavailable();
        return false;
      }
      return active;
    };
    const finish = () => {
      if (!check()) return;
      const anchor = geometry(cursor);
      if (!validAnchor(anchor)) return; // A zero-area region remains a draft, never a submitted anchor.
      hide();
      send("captured", anchor);
    };
    addEventListener("message", (event) => {
      const c = event.data;
      if (
        event.source !== parent ||
        event.origin !== platformOrigin ||
        !c ||
        c.type !== "PROTOTYPE_REVIEW_ANCHOR_COMMAND"
      )
        return;
      if (
        Object.keys(c).some(
          (key) =>
            ![
              "type",
              "channel",
              "generation",
              "resourcePath",
              "prefixLength",
              "action",
              "shape",
              "anchor",
            ].includes(key),
        ) ||
        typeof c.channel !== "string" ||
        !/^[A-Za-z0-9_-]{1,128}$/.test(c.channel) ||
        !Number.isSafeInteger(c.generation) ||
        c.generation < 1 ||
        !Number.isInteger(c.prefixLength) ||
        c.prefixLength < 1 ||
        c.prefixLength > 8192 ||
        typeof c.resourcePath !== "string" ||
        c.resourcePath.length < 1 ||
        c.resourcePath.length > 4096 ||
        c.resourcePath.startsWith("/") ||
        /[?#\\\x00-\x20\x7f]/.test(c.resourcePath) ||
        !["ready", "capture", "locate", "clear"].includes(c.action)
      )
        return;
      if (
        command &&
        command.channel === c.channel &&
        c.generation <= command.generation
      )
        return;
      hide();
      command = c;
      if (c.action === "clear") {
        send("ready");
        return;
      }
      if (!located()) {
        unavailable();
        return;
      }
      if (c.action === "ready") {
        send("ready");
        return;
      }
      if (
        c.action === "capture" &&
        !["point", "rect", "ellipse"].includes(c.shape)
      )
        return;
      if (c.action === "locate" && !validAnchor(c.anchor)) return;
      enabled = false;
      draw(null);
      relay({ type: "AXURE_INSPECT_MODE", enabled: false });
      snapshot = c.action === "locate" ? c.anchor : dimensions();
      if (
        !sameDimensions(snapshot) ||
        !validAnchor({
          space: "root-document-css-px-v1",
          shape: "point",
          x: 0,
          y: 0,
          width: 0,
          height: 0,
          ...snapshot,
        })
      ) {
        unavailable();
        return;
      }
      active = true;
      capture = c.action === "capture";
      surface.style.display = "block";
      surface.style.pointerEvents = capture ? "auto" : "none";
      surface.style.cursor = capture ? "crosshair" : "default";
      cursor = {
        x: Math.round(scrollX + innerWidth / 2),
        y: Math.round(scrollY + innerHeight / 2),
      };
      if (capture) {
        paint({ ...geometry(cursor), shape: "point" });
        surface.focus({ preventScroll: true });
        send("capturing");
      } else {
        scrollTo({
          left: Math.max(0, c.anchor.x - innerWidth / 3),
          top: Math.max(0, c.anchor.y - innerHeight / 3),
          behavior: "instant",
        });
        paint(c.anchor);
        send("located");
      }
      // pushState has no browser event; poll only while a short-lived drawing/marker is active.
      monitor = setInterval(check, 200);
    });
    surface.addEventListener("pointerdown", (event) => {
      if (!capture || !check() || event.button !== 0) return;
      event.preventDefault();
      event.stopPropagation();
      cursor = position(event);
      if (command.shape !== "point" && !first) first = cursor;
      surface.setPointerCapture(event.pointerId);
    });
    surface.addEventListener("pointermove", (event) => {
      if (!capture || !check()) return;
      cursor = position(event);
      paint(first ? geometry(cursor) : { ...geometry(cursor), shape: "point" });
    });
    surface.addEventListener("pointerup", (event) => {
      if (!capture || !check() || event.button !== 0) return;
      event.preventDefault();
      event.stopPropagation();
      cursor = position(event);
      finish();
    });
    surface.addEventListener("pointercancel", () => {
      if (capture) {
        hide();
        send("cancelled");
      }
    });
    surface.addEventListener("keydown", (event) => {
      if (!capture || !check()) return;
      if (event.key === "Escape") {
        event.preventDefault();
        hide();
        send("cancelled");
        return;
      }
      if (event.key === "Enter") {
        event.preventDefault();
        if (command.shape !== "point" && !first) first = { ...cursor };
        else finish();
        return;
      }
      const moves = {
        ArrowLeft: [-1, 0],
        ArrowRight: [1, 0],
        ArrowUp: [0, -1],
        ArrowDown: [0, 1],
      };
      if (!moves[event.key]) return;
      event.preventDefault();
      const step = event.shiftKey ? 10 : 1,
        delta = moves[event.key];
      cursor = {
        x: Math.max(
          0,
          Math.min(snapshot.documentWidth, cursor.x + delta[0] * step),
        ),
        y: Math.max(
          0,
          Math.min(snapshot.documentHeight, cursor.y + delta[1] * step),
        ),
      };
      paint(first ? geometry(cursor) : { ...geometry(cursor), shape: "point" });
    });
    const redraw = () => {
      if (check())
        paint(
          capture
            ? first
              ? geometry(cursor)
              : { ...geometry(cursor), shape: "point" }
            : command.anchor,
        );
    };
    addEventListener("scroll", redraw, true);
    addEventListener("resize", redraw);
    addEventListener("hashchange", () => {
      if (active) unavailable();
    });
    addEventListener("popstate", () => {
      if (active && !located()) unavailable();
    });
  }

  const fields = (style, keys) =>
    Object.fromEntries(keys.map((key) => [key, style[key]]));
  const inspect = (element) => {
    const rect = element.getBoundingClientRect();
    const style = getComputedStyle(element);
    const assets = [...element.querySelectorAll("img,svg")];
    if (element.matches("img,svg")) assets.unshift(element);
    const result = {
      tagName: element.tagName,
      id: element.id || "",
      className: typeof element.className === "string" ? element.className : "",
      text: (element.textContent || "").trim().slice(0, 1000),
      position: {
        x: rect.x,
        y: rect.y,
        pageX: rect.x + scrollX,
        pageY: rect.y + scrollY,
      },
      size: { width: rect.width, height: rect.height },
      typography: fields(style, [
        "fontFamily",
        "fontSize",
        "fontWeight",
        "lineHeight",
        "letterSpacing",
        "textAlign",
        "color",
      ]),
      appearance: fields(style, [
        "backgroundColor",
        "backgroundImage",
        "border",
        "borderRadius",
        "boxShadow",
        "opacity",
      ]),
      spacing: fields(style, [
        "marginTop",
        "marginRight",
        "marginBottom",
        "marginLeft",
        "paddingTop",
        "paddingRight",
        "paddingBottom",
        "paddingLeft",
      ]),
      html: element.outerHTML,
      assets: assets
        .slice(0, 100)
        .map((asset) =>
          asset.tagName.toLowerCase() === "svg"
            ? { type: "svg", content: asset.outerHTML }
            : { type: "image", url: asset.currentSrc || asset.src },
        ),
    };
    const background = /url\(["']?([^"')]+)["']?\)/.exec(style.backgroundImage);
    if (background)
      result.assets.push({
        type: "image",
        url: new URL(background[1], location.href).href,
      });
    return result;
  };
  addEventListener("message", (event) => {
    if (
      (event.origin !== platformOrigin && event.origin !== "null") ||
      (event.source !== parent && event.source !== top)
    )
      return;
    if (event.data?.type === "AXURE_INSPECT_MODE") {
      enabled = Boolean(event.data.enabled);
      if (!enabled) draw(null);
      relay({ type: "AXURE_INSPECT_MODE", enabled });
    }
  });
  document.addEventListener(
    "pointermove",
    (event) => {
      if (enabled && event.target !== marker) draw(event.target);
    },
    true,
  );
  document.addEventListener(
    "click",
    (event) => {
      if (!enabled || event.target === marker) return;
      event.preventDefault();
      event.stopImmediatePropagation();
      top.postMessage(
        { type: "AXURE_INSPECT_ELEMENT", payload: inspect(event.target) },
        "*",
      );
    },
    true,
  );
  addEventListener("scroll", () => draw(selected), true);
  addEventListener("resize", () => draw(selected));
  document.addEventListener(
    "load",
    (event) => {
      if (enabled && event.target.tagName === "IFRAME")
        event.target.contentWindow.postMessage(
          { type: "AXURE_INSPECT_MODE", enabled: true },
          "*",
        );
    },
    true,
  );
})();
