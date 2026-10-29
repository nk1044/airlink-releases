// Fade sections in as they scroll into view, and show the latest release's version.
(() => {
  document.documentElement.classList.remove("no-js");
  const reveal = document.querySelectorAll(".reveal");
  const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  if (reduceMotion || !("IntersectionObserver" in window)) {
    reveal.forEach((el) => el.classList.add("visible"));
  } else {
    const observer = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        if (entry.isIntersecting) {
          entry.target.classList.add("visible");
          observer.unobserve(entry.target);
        }
      }
    }, { rootMargin: "0px 0px -8% 0px", threshold: 0.08 });
    reveal.forEach((el) => observer.observe(el));
  }

  // The download links always point at the latest release's files; this only adds versions and sizes.
  fetch("https://api.github.com/repos/nk1044/airlink-releases/releases/latest", { headers: { Accept: "application/vnd.github+json" } })
    .then((response) => (response.ok ? response.json() : null))
    .then((release) => {
      if (!release || !release.tag_name) return;
      const version = release.tag_name.replace(/^v/, "");
      const assets = release.assets || [];
      const sizeOf = (name) => {
        const asset = assets.find((a) => a.name === name);
        return asset ? `${(asset.size / 1048576).toFixed(1)} MB` : "";
      };
      const dmgSize = sizeOf("AirLink.dmg");
      const label = document.getElementById("release-version");
      if (label) label.textContent = `Version ${version}${dmgSize && ` · ${dmgSize}`} · Free and open source`;
      document.querySelectorAll("[data-asset]").forEach((el) => {
        const size = sizeOf(el.dataset.asset);
        if (size) el.textContent = `Version ${version} · ${size}`;
      });
    })
    .catch(() => {});
})();
