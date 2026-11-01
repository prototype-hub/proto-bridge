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

    // A separate parent-only channel keeps root snapshots out of recursive legacy Inspect.
    const toolSurface = document.createElement("div");
    toolSurface.dataset.inspectToolSurface = "true";
    toolSurface.tabIndex = 0;
    toolSurface.setAttribute("role", "application");
    toolSurface.setAttribute(
      "aria-label",
      "Select root element / 选择根页面元素",
    );
    toolSurface.style.cssText =
      "position:fixed;inset:0;z-index:2147483647;display:none;outline:2px solid #1677ff;cursor:crosshair;touch-action:none;transition:none!important;animation:none!important";
    const toolMarker = document.createElement("div");
    toolMarker.dataset.inspectToolMarker = "true";
    toolMarker.style.cssText =
      "position:absolute;pointer-events:none;border:2px solid #1677ff;background:#1677ff26;box-sizing:border-box;transition:none!important;animation:none!important";
    toolSurface.appendChild(toolMarker);
    document.documentElement.appendChild(toolSurface);
    let toolCommand,
      picking = false,
      candidate,
      candidates = [],
      toolSelections = new Map(),
      summaryAccepted = false,
      toolMonitor;
    const owned = (node) =>
      node === marker || surface.contains(node) || toolSurface.contains(node);
    const excluded = (element) =>
      owned(element) ||
      element.closest(
        "head,script,style,link,meta,title,base,iframe,frame,noscript,template",
      );
    const toolLocated = () =>
      toolCommand &&
      !location.search &&
      !location.hash &&
      location.pathname.slice(toolCommand.prefixLength) ===
        toolCommand.resourcePath;
    const toolSend = (status, payload) => {
      if (toolCommand)
        parent.postMessage(
          {
            type: "PROTOTYPE_INSPECT_TOOL_EVENT",
            channel: toolCommand.channel,
            generation: toolCommand.generation,
            status,
            ...payload,
          },
          platformOrigin,
        );
    };
    const hidePicker = () => {
      picking = false;
      candidate = undefined;
      candidates = [];
      toolSurface.style.display = "none";
      if (!toolSelections.size && !summaryAccepted) clearInterval(toolMonitor);
    };
    const resetTools = () => {
      hidePicker();
      toolSelections.clear();
      summaryAccepted = false;
      clearInterval(toolMonitor);
    };
    const invalidateTools = () => {
      if (!picking && !toolSelections.size && !summaryAccepted) return;
      const status = picking ? "unavailable" : "stale";
      resetTools();
      toolSend(status);
    };
    const rendered = (element) => {
      if (!element?.isConnected || excluded(element)) return;
      const rect = element.getBoundingClientRect();
      const style = getComputedStyle(element);
      if (
        rect.width > 0 &&
        rect.height > 0 &&
        style.display !== "none" &&
        style.visibility !== "hidden" &&
        style.visibility !== "collapse"
      )
        return { rect, style };
    };
    const rootRect = (element) => {
      const renderedElement = rendered(element);
      if (!renderedElement) return;
      const rect = renderedElement.rect;
      const result = {
        x: Math.round((rect.x + scrollX) * 100) / 100,
        y: Math.round((rect.y + scrollY) * 100) / 100,
        width: Math.round(rect.width * 100) / 100,
        height: Math.round(rect.height * 100) / 100,
      };
      if (
        Object.values(result).every(Number.isFinite) &&
        Math.abs(result.x) <= 1000000 &&
        Math.abs(result.y) <= 1000000 &&
        result.width > 0 &&
        result.height > 0 &&
        result.width <= 1000000 &&
        result.height <= 1000000
      )
        return result;
    };
    const paintCandidate = (element, scroll = false) => {
      candidate = rootRect(element) ? element : undefined;
      if (!candidate) {
        toolMarker.style.display = "none";
        return;
      }
      if (scroll)
        candidate.scrollIntoView({
          block: "center",
          inline: "nearest",
          behavior: "instant",
        });
      const rect = candidate.getBoundingClientRect();
      Object.assign(toolMarker.style, {
        display: "block",
        left: rect.x + "px",
        top: rect.y + "px",
        width: rect.width + "px",
        height: rect.height + "px",
      });
    };
    // Hidden nodes consume the budget too; neither scan enters frames or shadow roots.
    const walkRoot = (visit) => {
      const deadline = performance.now() + 100;
      const walker = document.createTreeWalker(
        document.documentElement,
        NodeFilter.SHOW_ELEMENT,
      );
      let element = walker.currentNode,
        visited = 0,
        truncated = false;
      while (element) {
        if (!owned(element)) {
          if (visited >= 2000 || performance.now() >= deadline) {
            truncated = true;
            break;
          }
          visited++;
          const data = rendered(element);
          if (data && visit(element, data, deadline) === false) {
            truncated = true;
            break;
          }
        }
        element = walker.nextNode();
      }
      return {
        visited,
        truncated:
          truncated || visited >= 2000 || performance.now() >= deadline,
      };
    };
    const styleSnapshot = () => {
      const colors = new Map(),
        fonts = new Map();
      let capped = false;
      const bounded = (value) => {
        if (value.length > 256) capped = true;
        return value.slice(0, 256);
      };
      const add = (map, key, value) => {
        if (map.has(key)) map.get(key).count++;
        else if (map.size < 100) map.set(key, { ...value, count: 1 });
        else capped = true;
      };
      const color = (value) => {
        if (
          value === "transparent" ||
          /^rgba\([^)]*,\s*0(?:\.0+)?\s*\)$/.test(value) ||
          /\/\s*0(?:\.0+)?%?\s*\)$/.test(value)
        )
          return;
        value = bounded(value);
        add(colors, value, { value });
      };
      const scan = walkRoot((element, { style }, deadline) => {
        let directText = false;
        for (
          let node = element.firstChild;
          node && !directText;
          node = node.nextSibling
        ) {
          if (performance.now() >= deadline) return false;
          if (node.nodeType !== Node.TEXT_NODE) continue;
          const text = node.textContent;
          for (let offset = 0; offset < text.length; offset += 1024) {
            if (performance.now() >= deadline) return false;
            if (/\S/.test(text.slice(offset, offset + 1024))) {
              directText = true;
              break;
            }
          }
        }
        if (directText) {
          color(style.color);
          const font = {
            family: bounded(style.fontFamily),
            size: bounded(style.fontSize),
            weight: bounded(style.fontWeight),
            lineHeight: bounded(style.lineHeight),
          };
          add(fonts, JSON.stringify(font), font);
        }
        color(style.backgroundColor);
        for (const side of ["Top", "Right", "Bottom", "Left"])
          if (
            parseFloat(style["border" + side + "Width"]) > 0 &&
            !["none", "hidden"].includes(style["border" + side + "Style"])
          )
            color(style["border" + side + "Color"]);
      });
      const ordered = (map) =>
        [...map.entries()]
          .sort(
            ([a, x], [b, y]) =>
              y.count - x.count || (a < b ? -1 : a > b ? 1 : 0),
          )
          .map(([, value]) => value);
      return {
        visited: scan.visited,
        truncated: scan.truncated || capped,
        colors: ordered(colors),
        fonts: ordered(fonts),
      };
    };
    const monitorTools = () => {
      clearInterval(toolMonitor);
      // Only selected rectangles are polled; snapshots never trigger repeated whole-DOM scans.
      toolMonitor = setInterval(() => {
        if (!enabled) {
          resetTools();
          return;
        }
        if (
          !toolLocated() ||
          [...toolSelections.values()].some(
            ({ element, rect }) =>
              JSON.stringify(rootRect(element)) !== JSON.stringify(rect),
          )
        ) {
          invalidateTools();
          return;
        }
        if (picking) paintCandidate(candidate);
      }, 200);
    };
    const selectCandidate = () => {
      if (!picking) return;
      const element = candidate,
        rect = rootRect(element);
      hidePicker();
      if (!rect || !toolLocated() || !enabled) {
        toolSend("unavailable");
        return;
      }
      toolSelections.set(toolCommand.slot, { element, rect });
      toolSend("selected", {
        selection: {
          slot: toolCommand.slot,
          tagName: element.tagName.slice(0, 64),
          id: (element.id || "").slice(0, 256),
          rect,
        },
      });
      monitorTools();
    };
    const pointCandidate = (event) => {
      toolSurface.style.pointerEvents = "none";
      const element = document.elementFromPoint(event.clientX, event.clientY);
      toolSurface.style.pointerEvents = "auto";
      paintCandidate(element);
      return element;
    };
    addEventListener("message", (event) => {
      if (
        event.source === parent &&
        event.origin === platformOrigin &&
        event.data?.type === "AXURE_INSPECT_MODE" &&
        !event.data.enabled
      )
        resetTools();
      const c = event.data;
      if (
        event.source !== parent ||
        event.origin !== platformOrigin ||
        !c ||
        typeof c !== "object" ||
        Array.isArray(c) ||
        c.type !== "PROTOTYPE_INSPECT_TOOL_COMMAND"
      )
        return;
      if (
        Object.keys(c).length !== (c.action === "pick" ? 7 : 6) ||
        Object.keys(c).some(
          (key) =>
            ![
              "type",
              "channel",
              "generation",
              "action",
              "resourcePath",
              "prefixLength",
              "slot",
            ].includes(key),
        ) ||
        typeof c.channel !== "string" ||
        !/^[A-Za-z0-9_-]{16,128}$/.test(c.channel) ||
        !Number.isInteger(c.generation) ||
        c.generation < 1 ||
        c.generation > 2147483647 ||
        !Number.isInteger(c.prefixLength) ||
        c.prefixLength < 1 ||
        c.prefixLength > 8192 ||
        typeof c.resourcePath !== "string" ||
        c.resourcePath.length < 1 ||
        c.resourcePath.length > 4096 ||
        c.resourcePath.startsWith("/") ||
        /[?#\\\x00-\x20\x7f]/.test(c.resourcePath) ||
        !["ready", "pick", "summary", "clear"].includes(c.action) ||
        (c.action === "pick" ? !["A", "B"].includes(c.slot) : "slot" in c)
      )
        return;
      if (
        toolCommand &&
        toolCommand.channel === c.channel &&
        c.generation <= toolCommand.generation
      )
        return;
      hidePicker();
      toolCommand = c;
      if (c.action === "clear") {
        resetTools();
        toolSend("ready");
        return;
      }
      if (!toolLocated() || (c.action !== "ready" && !enabled)) {
        resetTools();
        toolSend("unavailable");
        return;
      }
      if (c.action === "ready") {
        toolSend("ready");
        return;
      }
      if (c.action === "summary") {
        const summary = styleSnapshot();
        summaryAccepted = true;
        toolSend("summary", { summary });
        monitorTools();
        return;
      }
      candidates = [];
      walkRoot((element) => {
        if (rootRect(element)) candidates.push(element);
      });
      if (!candidates.length) {
        toolSend("unavailable");
        return;
      }
      picking = true;
      draw(null);
      toolSurface.style.display = "block";
      toolSurface.style.pointerEvents = "auto";
      paintCandidate(candidates[0]);
      toolSurface.focus({ preventScroll: true });
      toolSend("ready");
      monitorTools();
    });
    document.addEventListener(
      "pointermove",
      (event) => {
        if (!picking) return;
        event.stopImmediatePropagation();
        pointCandidate(event);
      },
      true,
    );
    document.addEventListener(
      "click",
      (event) => {
        if (!picking) return;
        event.preventDefault();
        event.stopImmediatePropagation();
        pointCandidate(event);
        selectCandidate();
      },
      true,
    );
    toolSurface.addEventListener("keydown", (event) => {
      if (!picking) return;
      if (event.key === "Escape") {
        event.preventDefault();
        hidePicker();
        toolSend("cancelled");
      } else if (event.key === "Enter") {
        event.preventDefault();
        selectCandidate();
      } else if (event.key === "Tab") {
        event.preventDefault();
      } else if (
        ["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown"].includes(event.key)
      ) {
        event.preventDefault();
        const step = ["ArrowRight", "ArrowDown"].includes(event.key) ? 1 : -1;
        const index = candidates.indexOf(candidate);
        paintCandidate(
          candidates[(index + step + candidates.length) % candidates.length],
          true,
        );
      }
    });
    new MutationObserver((records) => {
      if (records.some((record) => !owned(record.target))) invalidateTools();
    }).observe(document.documentElement, {
      subtree: true,
      attributes: true,
      childList: true,
      characterData: true,
    });
    addEventListener(
      "scroll",
      () => {
        if (picking) paintCandidate(candidate);
      },
      true,
    );
    for (const name of ["resize", "hashchange", "popstate"])
      addEventListener(name, invalidateTools, true);
    for (const name of [
      "transitionrun",
      "transitionend",
      "transitioncancel",
      "animationstart",
      "animationiteration",
      "animationend",
      "animationcancel",
    ])
      addEventListener(
        name,
        (event) => {
          if (!owned(event.target)) invalidateTools();
        },
        true,
      );
    document.fonts?.addEventListener("loadingdone", invalidateTools);
    document.addEventListener(
      "load",
      (event) => {
        if (event.target.matches?.('link[rel~="stylesheet"]'))
          invalidateTools();
      },
      true,
    );
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
