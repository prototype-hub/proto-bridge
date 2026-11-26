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

  // Each sandboxed document owns its DOM. Descending is explicit and uses only direct-window relays.
  (() => {
    if (window === top) return;
    const root = parent === top;
    const commandType = root
      ? "PROTOTYPE_REVIEW_ELEMENT_COMMAND"
      : "PROTOTYPE_REVIEW_ELEMENT_RELAY_COMMAND";
    const eventType = root
      ? "PROTOTYPE_REVIEW_ELEMENT_EVENT"
      : "PROTOTYPE_REVIEW_ELEMENT_RELAY_EVENT";
    const idPattern = /^[A-Za-z][A-Za-z0-9_.:-]{0,127}$/;
    const record = (value) =>
      value && typeof value === "object" && !Array.isArray(value);
    const keys = (value, allowed) =>
      Object.keys(value).length === allowed.length &&
      Object.keys(value).every((key) => allowed.includes(key));
    const pathValid = (value) => {
      if (
        typeof value !== "string" ||
        !value.length ||
        value.length > 4096 ||
        value.split("/")[0].includes(":") ||
        /[?#\\\x00-\x20\x7f-\uffff]/.test(value)
      )
        return false;
      try {
        return value.split("/").every((part) => {
          if (!part || /%(?![\da-f]{2})/i.test(part)) return false;
          const decoded = decodeURIComponent(part);
          return (
            ![".", ".."].includes(decoded) &&
            !/[/\\\x00-\x1f\x7f]/.test(decoded)
          );
        });
      } catch (_) {
        return false;
      }
    };
    const targetValid = (target) =>
      record(target) &&
      keys(target, [
        "space",
        "frames",
        "elementId",
        "tagName",
        "textHint",
        "viewportWidth",
        "viewportHeight",
      ]) &&
      target.space === "frame-element-v1" &&
      typeof target.elementId === "string" &&
      idPattern.test(target.elementId) &&
      typeof target.tagName === "string" &&
      /^[a-z][a-z0-9]{0,31}$/.test(target.tagName) &&
      typeof target.textHint === "string" &&
      target.textHint.length <= 160 &&
      !Array.from(target.textHint).some(
        (value) =>
          value.charCodeAt(0) < 32 ||
          (value.length === 1 &&
            value.charCodeAt(0) >= 0xd800 &&
            value.charCodeAt(0) <= 0xdfff),
      ) &&
      [target.viewportWidth, target.viewportHeight].every(
        (value) => Number.isInteger(value) && value >= 1 && value <= 16384,
      ) &&
      Array.isArray(target.frames) &&
      target.frames.length <= 4 &&
      target.frames.every(
        (frame) =>
          record(frame) &&
          keys(frame, ["elementId", "resourcePath"]) &&
          typeof frame.elementId === "string" &&
          idPattern.test(frame.elementId) &&
          pathValid(frame.resourcePath),
      );
    const sameLeaf = (a, b) =>
      [
        "space",
        "elementId",
        "tagName",
        "textHint",
        "viewportWidth",
        "viewportHeight",
      ].every((key) => a[key] === b[key]);
    const nonce = () =>
      Array.from(crypto.getRandomValues(new Uint8Array(16)), (v) =>
        v.toString(16).padStart(2, "0"),
      ).join("");
    const retiredChannels = new Set();
    let command,
      child,
      monitor,
      picking = false,
      active = false,
      candidate,
      candidates = [],
      localTarget;
    const surface = document.createElement("div");
    surface.dataset.reviewElementSurface = "true";
    surface.tabIndex = 0;
    surface.setAttribute("role", "application");
    surface.setAttribute(
      "aria-label",
      "Choose element: arrows, Enter to select, Tab for controls, Escape to cancel / 选择元素：方向键、回车选择、Tab切换操作、Esc取消",
    );
    surface.style.cssText =
      "position:fixed;inset:0;z-index:2147483647;display:none;outline:2px solid #1677ff;touch-action:none;cursor:crosshair";
    const selection = document.createElement("div");
    selection.dataset.reviewElementMarker = "true";
    selection.style.cssText =
      "position:fixed;pointer-events:none;border:2px solid #1677ff;background:#1677ff26;box-sizing:border-box;display:none;transition:none!important;animation:none!important";
    const controls = document.createElement("div");
    controls.dataset.reviewElementControls = "true";
    controls.style.cssText =
      "position:absolute;left:8px;top:8px;max-width:calc(100% - 16px);padding:8px;background:#fff;color:#172033;border:1px solid #1677ff;border-radius:6px;font:14px/1.4 system-ui;cursor:default;box-sizing:border-box";
    const label = document.createElement("div");
    label.style.cssText =
      "max-width:360px;overflow-wrap:anywhere;margin-bottom:6px";
    label.setAttribute("aria-live", "polite");
    const enter = document.createElement("button");
    enter.type = "button";
    enter.textContent = "Enter frame / 进入内嵌页面";
    const cancel = document.createElement("button");
    cancel.type = "button";
    cancel.textContent = "Cancel / 取消";
    for (const button of [enter, cancel])
      button.style.cssText =
        "font:inherit;padding:6px 8px;margin:0 4px 0 0;min-height:44px;cursor:pointer";
    controls.append(label, enter, cancel);
    surface.append(selection, controls);
    document.documentElement.appendChild(surface);
    const owned = (element) =>
      element === marker ||
      surface.contains(element) ||
      element.closest?.(
        "[data-review-anchor-surface],[data-inspect-tool-surface]",
      );
    const currentPath = () =>
      command &&
      !location.search &&
      !location.hash &&
      location.pathname.slice(0, command.prefixLength).endsWith("/") &&
      location.pathname.slice(command.prefixLength) === command.resourcePath;
    const hint = (element) =>
      (element.getAttribute("data-label") || element.textContent || "")
        .replace(/\s+/g, " ")
        .trim()
        .slice(0, 160)
        .replace(/[\ud800-\udbff]$/, "");
    const unique = (element) =>
      idPattern.test(element?.id || "") &&
      document.querySelectorAll('[id="' + element.id + '"]').length === 1;
    const visible = (element) => {
      if (
        !element?.isConnected ||
        owned(element) ||
        element.closest(
          "head,script,style,link,meta,title,base,noscript,template",
        )
      )
        return false;
      const rect = element.getBoundingClientRect(),
        style = getComputedStyle(element);
      for (let ancestor = element; ancestor; ancestor = ancestor.parentElement)
        if (Number(getComputedStyle(ancestor).opacity) === 0) return false;
      return (
        rect.width > 0 &&
        rect.height > 0 &&
        style.display !== "none" &&
        style.visibility !== "hidden" &&
        style.visibility !== "collapse"
      );
    };
    const targetFor = (element) => {
      if (!visible(element) || !unique(element)) return;
      const target = {
        space: "frame-element-v1",
        frames: [],
        elementId: element.id,
        tagName: element.tagName.toLowerCase(),
        textHint: hint(element),
        viewportWidth: innerWidth,
        viewportHeight: innerHeight,
      };
      return targetValid(target) ? target : undefined;
    };
    const framePath = (frame) => {
      if (
        !command ||
        command.depth >= 4 ||
        frame?.tagName !== "IFRAME" ||
        !targetFor(frame) ||
        frame.hasAttribute("srcdoc")
      )
        return;
      try {
        const raw = frame.getAttribute("src");
        if (!raw) return;
        const value = new URL(raw, document.baseURI),
          own = new URL(location.href),
          prefix = own.pathname.slice(0, command.prefixLength),
          path = value.pathname.slice(command.prefixLength);
        if (
          prefix.endsWith("/") &&
          value.origin === own.origin &&
          value.pathname.startsWith(prefix) &&
          !value.search &&
          !value.hash &&
          pathValid(path)
        )
          return path;
      } catch (_) {}
    };
    const send = (status, target) => {
      if (!command) return;
      parent.postMessage(
        {
          type: eventType,
          channel: command.channel,
          generation: command.generation,
          status,
          ...(target ? { elementTarget: target } : {}),
        },
        root ? platformOrigin : "*",
      );
    };
    const retireChild = () => {
      if (!child) return;
      clearTimeout(child.timer);
      try {
        const { elementTarget, ...base } = child.command;
        child.window.postMessage(
          {
            ...base,
            generation: child.command.generation + 1,
            action: "clear",
          },
          "*",
        );
      } catch (_) {}
      child = undefined;
    };
    const stop = () => {
      retireChild();
      clearInterval(monitor);
      picking = active = false;
      candidate = localTarget = undefined;
      candidates = [];
      surface.style.display = selection.style.display = "none";
    };
    const unavailable = () => {
      stop();
      send("unavailable");
    };
    const paint = (element) => {
      candidate = targetFor(element) ? element : undefined;
      const rect = candidate?.getBoundingClientRect();
      selection.style.display = rect ? "block" : "none";
      if (rect)
        Object.assign(selection.style, {
          left: rect.x + "px",
          top: rect.y + "px",
          width: rect.width + "px",
          height: rect.height + "px",
        });
      enter.disabled = !framePath(candidate);
      label.textContent = candidate
        ? (hint(candidate) || candidate.tagName.toLowerCase()) +
          " · #" +
          candidate.id
        : "Choose a visible element with a unique ID / 请选择有唯一ID的可见元素";
    };
    const scan = () => {
      candidates = [];
      const walker = document.createTreeWalker(
          document.documentElement,
          NodeFilter.SHOW_ELEMENT,
        ),
        deadline = performance.now() + 100;
      let element = walker.currentNode,
        count = 0;
      while (element && count++ < 2000 && performance.now() < deadline) {
        if (targetFor(element)) candidates.push(element);
        element = walker.nextNode();
      }
    };
    const startPicker = (message) => {
      picking = active = true;
      surface.style.display = "block";
      surface.style.pointerEvents = "auto";
      controls.style.display = "block";
      scan();
      paint(candidate?.isConnected ? candidate : candidates[0]);
      if (message) label.textContent = message;
      surface.focus({ preventScroll: true });
    };
    const finish = () => {
      const target = targetFor(candidate);
      if (!picking || !currentPath() || !target) return;
      stop();
      send("captured", target);
    };
    const childFailed = () => {
      const wasCapture = command?.action === "capture";
      retireChild();
      if (wasCapture && currentPath()) {
        startPicker(
          "Cannot enter this frame. Select its outer element or another element / 此内嵌页面不可进入，可手动选择外框或其他元素",
        );
      } else unavailable();
    };
    const descend = (frame, path, target) => {
      retireChild();
      const next = {
        type: "PROTOTYPE_REVIEW_ELEMENT_RELAY_COMMAND",
        channel: nonce(),
        generation: 1,
        action: target ? "locate" : "capture",
        resourcePath: path,
        prefixLength: command.prefixLength,
        depth: command.depth + 1,
        ...(target ? { elementTarget: target } : {}),
      };
      child = {
        element: frame,
        elementId: frame.id,
        window: frame.contentWindow,
        command: next,
        path,
        started: false,
      };
      picking = false;
      if (!target) surface.style.display = "none";
      const expectedChild = child;
      child.timer = setTimeout(() => {
        if (child === expectedChild) childFailed();
      }, 2500);
      try {
        child.window.postMessage(next, "*");
      } catch (_) {
        childFailed();
      }
    };
    const enterFrame = () => {
      const path = framePath(candidate);
      if (!picking || !path) return;
      descend(candidate, path);
    };
    const check = () => {
      if (!active) return;
      if (!currentPath()) {
        unavailable();
        return;
      }
      if (child) {
        if (
          !visible(child.element) ||
          !unique(child.element) ||
          child.element.id !== child.elementId ||
          framePath(child.element) !== child.path ||
          child.element.contentWindow !== child.window
        )
          unavailable();
      } else if (command.action === "locate") {
        const now = targetFor(candidate);
        if (!now || !sameLeaf(now, localTarget)) unavailable();
        else paint(candidate);
      } else if (candidate && !targetFor(candidate)) paint(undefined);
    };
    addEventListener("message", (event) => {
      const c = event.data;
      if (
        child &&
        event.source === child.window &&
        event.origin === "null" &&
        record(c) &&
        c.type === "PROTOTYPE_REVIEW_ELEMENT_RELAY_EVENT" &&
        c.channel === child.command.channel &&
        c.generation === child.command.generation &&
        keys(c, [
          "type",
          "channel",
          "generation",
          "status",
          ...(c.status === "captured" ? ["elementTarget"] : []),
        ])
      ) {
        if (
          ![
            "capturing",
            "captured",
            "cancelled",
            "located",
            "unavailable",
          ].includes(c.status)
        )
          return;
        if (c.status === "capturing" && command.action !== "capture") return;
        if (
          c.status === "captured" &&
          (!child.started ||
            command.action !== "capture" ||
            !targetValid(c.elementTarget) ||
            c.elementTarget.frames.length + command.depth + 1 > 4)
        )
          return;
        if (c.status === "located" && command.action !== "locate") return;
        clearTimeout(child.timer);
        child.started = true;
        if (c.status === "unavailable") {
          childFailed();
          return;
        }
        if (c.status === "cancelled") {
          stop();
          send("cancelled");
          return;
        }
        if (c.status === "captured") {
          const target = {
            ...c.elementTarget,
            frames: [
              { elementId: child.element.id, resourcePath: child.path },
              ...c.elementTarget.frames,
            ],
          };
          stop();
          send("captured", target);
        } else if (c.status === "located") send("located");
        return;
      }
      if (
        event.source !== parent ||
        event.origin !== (root ? platformOrigin : "null") ||
        !record(c)
      )
        return;
      if (
        (c.type === "AXURE_INSPECT_MODE" && c.enabled) ||
        (root &&
          [
            "PROTOTYPE_REVIEW_ANCHOR_COMMAND",
            "PROTOTYPE_INSPECT_TOOL_COMMAND",
          ].includes(c.type))
      ) {
        stop();
        return;
      }
      if (
        c.type !== commandType ||
        !keys(c, [
          "type",
          "channel",
          "generation",
          "action",
          "resourcePath",
          "prefixLength",
          ...(!root ? ["depth"] : []),
          ...(c.action === "locate" ? ["elementTarget"] : []),
        ]) ||
        typeof c.channel !== "string" ||
        !/^[A-Za-z0-9_-]{1,128}$/.test(c.channel) ||
        !Number.isSafeInteger(c.generation) ||
        c.generation < 1 ||
        !Number.isInteger(c.prefixLength) ||
        c.prefixLength < 1 ||
        c.prefixLength > 8192 ||
        !pathValid(c.resourcePath) ||
        !["ready", "capture", "locate", "clear"].includes(c.action) ||
        (!root && (!Number.isInteger(c.depth) || c.depth < 1 || c.depth > 4)) ||
        (c.action === "locate" && !targetValid(c.elementTarget)) ||
        retiredChannels.has(c.channel) ||
        (command &&
          c.channel === command.channel &&
          c.generation <= command.generation)
      )
        return;
      stop();
      if (command && command.channel !== c.channel)
        retiredChannels.add(command.channel);
      command = { ...c, depth: root ? 0 : c.depth };
      if (c.action === "clear") {
        send("ready");
        return;
      }
      if (!currentPath()) {
        unavailable();
        return;
      }
      if (c.action === "ready") {
        send("ready");
        return;
      }
      enabled = false;
      draw(null);
      relay({ type: "AXURE_INSPECT_MODE", enabled: false });
      active = true;
      monitor = setInterval(check, 150);
      if (c.action === "capture") {
        startPicker();
        send("capturing");
        return;
      }
      const target = c.elementTarget;
      if (target.frames.length) {
        const hop = target.frames[0],
          frame = document.getElementById(hop.elementId);
        if (
          command.depth + target.frames.length > 4 ||
          framePath(frame) !== hop.resourcePath
        ) {
          unavailable();
          return;
        }
        frame.scrollIntoView({
          block: "center",
          inline: "nearest",
          behavior: "instant",
        });
        paint(frame);
        surface.style.display = "block";
        surface.style.pointerEvents = "none";
        controls.style.display = "none";
        descend(frame, hop.resourcePath, {
          ...target,
          frames: target.frames.slice(1),
        });
      } else {
        const element = document.getElementById(target.elementId),
          now = targetFor(element);
        if (!now || !sameLeaf(now, target)) {
          unavailable();
          return;
        }
        localTarget = now;
        element.scrollIntoView({
          block: "center",
          inline: "nearest",
          behavior: "instant",
        });
        paint(element);
        surface.style.display = "block";
        surface.style.pointerEvents = "none";
        controls.style.display = "none";
        send("located");
      }
    });
    const point = (event) => {
      surface.style.pointerEvents = "none";
      let element = document.elementFromPoint(event.clientX, event.clientY);
      surface.style.pointerEvents = "auto";
      while (element && !targetFor(element)) element = element.parentElement;
      paint(element);
    };
    document.addEventListener(
      "pointermove",
      (event) => {
        if (!picking || controls.contains(event.target)) return;
        event.stopImmediatePropagation();
        point(event);
      },
      true,
    );
    document.addEventListener(
      "click",
      (event) => {
        if (!picking || controls.contains(event.target)) return;
        event.preventDefault();
        event.stopImmediatePropagation();
        point(event);
        finish();
      },
      true,
    );
    surface.addEventListener("keydown", (event) => {
      if (!picking) return;
      if (event.key === "Escape") {
        event.preventDefault();
        stop();
        send("cancelled");
      } else if (event.key === "Tab") {
        event.preventDefault();
        const focusable = [
            surface,
            ...(!enter.disabled ? [enter] : []),
            cancel,
          ],
          index = focusable.indexOf(document.activeElement);
        focusable[
          (index + (event.shiftKey ? -1 : 1) + focusable.length) %
            focusable.length
        ].focus({ preventScroll: true });
      } else if (controls.contains(event.target)) return;
      else if (event.key === "Enter") {
        event.preventDefault();
        finish();
      } else if (
        ["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown"].includes(event.key)
      ) {
        event.preventDefault();
        scan();
        if (!candidates.length) return;
        const step = ["ArrowRight", "ArrowDown"].includes(event.key) ? 1 : -1,
          index = candidates.indexOf(candidate),
          next =
            candidates[(index + step + candidates.length) % candidates.length];
        next.scrollIntoView({
          block: "center",
          inline: "nearest",
          behavior: "instant",
        });
        paint(next);
      }
    });
    enter.addEventListener("click", enterFrame);
    cancel.addEventListener("click", () => {
      if (picking) {
        stop();
        send("cancelled");
      }
    });
    addEventListener(
      "scroll",
      () => {
        if (active && candidate) paint(candidate);
      },
      true,
    );
    addEventListener("resize", () => {
      if (active) unavailable();
    });
    addEventListener("pagehide", () => {
      if (active) unavailable();
    });
    document.addEventListener(
      "load",
      (event) => {
        if (child && event.target === child.element) childFailed();
      },
      true,
    );
    addEventListener("hashchange", check);
    addEventListener("popstate", check);
  })();

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
