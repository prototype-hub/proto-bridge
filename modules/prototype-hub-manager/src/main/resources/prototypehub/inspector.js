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
  marker.style.cssText = "position:fixed;pointer-events:none;border:2px solid #1677ff;background:#1677ff14;z-index:2147483647;display:none;box-sizing:border-box";
  document.documentElement.appendChild(marker);
  const relay = value => document.querySelectorAll("iframe").forEach(frame => {
    try { frame.contentWindow.postMessage(value, "*"); } catch (_) { }
  });
  const draw = element => {
    selected = element;
    if (!enabled || !element) { marker.style.display = "none"; return; }
    const r = element.getBoundingClientRect();
    Object.assign(marker.style, {display:"block",left:r.left+"px",top:r.top+"px",width:r.width+"px",height:r.height+"px"});
  };
  const fields = (style, keys) => Object.fromEntries(keys.map(key => [key, style[key]]));
  const inspect = element => {
    const rect = element.getBoundingClientRect();
    const style = getComputedStyle(element);
    const assets = [...element.querySelectorAll("img,svg")];
    if (element.matches("img,svg")) assets.unshift(element);
    const result = {
      tagName:element.tagName,id:element.id||"",className:typeof element.className==="string"?element.className:"",text:(element.textContent||"").trim().slice(0,1000),
      position:{x:rect.x,y:rect.y,pageX:rect.x+scrollX,pageY:rect.y+scrollY},size:{width:rect.width,height:rect.height},
      typography:fields(style,["fontFamily","fontSize","fontWeight","lineHeight","letterSpacing","textAlign","color"]),
      appearance:fields(style,["backgroundColor","backgroundImage","border","borderRadius","boxShadow","opacity"]),
      spacing:fields(style,["marginTop","marginRight","marginBottom","marginLeft","paddingTop","paddingRight","paddingBottom","paddingLeft"]),
      html:element.outerHTML,assets:assets.slice(0,100).map(asset => asset.tagName.toLowerCase()==="svg"?{type:"svg",content:asset.outerHTML}:{type:"image",url:asset.currentSrc||asset.src})
    };
    const background = /url\(["']?([^"')]+)["']?\)/.exec(style.backgroundImage);
    if (background) result.assets.push({type:"image",url:new URL(background[1],location.href).href});
    return result;
  };
  addEventListener("message", event => {
    if ((event.origin !== platformOrigin && event.origin !== "null") || (event.source !== parent && event.source !== top)) return;
    if (event.data?.type === "AXURE_INSPECT_MODE") {
      enabled = Boolean(event.data.enabled);
      if (!enabled) draw(null);
      relay({type:"AXURE_INSPECT_MODE",enabled});
    }
  });
  document.addEventListener("pointermove", event => { if(enabled && event.target!==marker) draw(event.target); }, true);
  document.addEventListener("click", event => {
    if (!enabled || event.target===marker) return;
    event.preventDefault(); event.stopImmediatePropagation();
    top.postMessage({type:"AXURE_INSPECT_ELEMENT",payload:inspect(event.target)}, "*");
  }, true);
  addEventListener("scroll", () => draw(selected), true);
  addEventListener("resize", () => draw(selected));
  document.addEventListener("load", event => {
    if (enabled && event.target.tagName==="IFRAME") event.target.contentWindow.postMessage({type:"AXURE_INSPECT_MODE",enabled:true},"*");
  }, true);
})();
