(() => {
	const storageKey = "trustvault-theme";
	const themes = ["light", "ocean", "forest", "dark"];
	const labels = {
		light: "Light",
		ocean: "Ocean",
		forest: "Forest",
		dark: "Dark"
	};
	const applyTheme = (theme) => {
		const selected = themes.includes(theme) ? theme : "light";
		document.documentElement.dataset.theme = selected;
		return selected;
	};

	const savedTheme = localStorage.getItem(storageKey);
	const initialTheme = applyTheme(savedTheme);

	window.addEventListener("storage", (event) => {
		if (event.key === storageKey) {
			applyTheme(event.newValue);
		}
	});

	document.addEventListener("DOMContentLoaded", () => {
		const picker = document.createElement("div");
		picker.className = "theme-picker";

		const label = document.createElement("label");
		label.htmlFor = "theme-choice";
		label.textContent = "Theme";

		const select = document.createElement("select");
		select.id = "theme-choice";
		select.setAttribute("aria-label", "Website theme");

		for (const theme of themes) {
			const option = document.createElement("option");
			option.value = theme;
			option.textContent = labels[theme];
			select.append(option);
		}
		select.value = initialTheme;
		select.addEventListener("change", () => {
			localStorage.setItem(storageKey, applyTheme(select.value));
		});

		picker.append(label, select);
		document.body.append(picker);
	});
})();
