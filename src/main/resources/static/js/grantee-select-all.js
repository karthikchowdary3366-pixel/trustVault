document.addEventListener('DOMContentLoaded', function () {
	var selectAll = document.getElementById('select-all-grantees');
	var search = document.getElementById('grantee-search');
	var picker = document.getElementById('grantee-list');
	var count = document.getElementById('grantee-count');
	if (!selectAll || !search || !picker || !count) {
		return;
	}
	var grantees = Array.from(picker.querySelectorAll('input[name="grantees"]'));
	var noMatch = document.getElementById('grantee-no-match');

	function visibleGrantees() {
		return grantees.filter(function (grantee) {
			return !grantee.closest('.grantee-option').hidden;
		});
	}

	function updateSelection() {
		var visible = visibleGrantees();
		var selectedVisible = visible.filter(function (grantee) {
			return grantee.checked;
		});
		var selectedTotal = grantees.filter(function (grantee) {
			return grantee.checked;
		}).length;

		selectAll.checked = visible.length > 0 && selectedVisible.length === visible.length;
		selectAll.indeterminate = selectedVisible.length > 0 && selectedVisible.length < visible.length;
		selectAll.disabled = visible.length === 0;
		count.textContent = selectedTotal + ' selected';
	}

	function filterGrantees() {
		var query = search.value.trim().toLowerCase();
		var visibleCount = 0;
		grantees.forEach(function (grantee) {
			var option = grantee.closest('.grantee-option');
			option.hidden = option.dataset.search.indexOf(query) === -1;
			if (!option.hidden) {
				visibleCount++;
			}
		});
		noMatch.hidden = visibleCount > 0 || grantees.length === 0;
		updateSelection();
	}

	search.addEventListener('input', filterGrantees);
	selectAll.addEventListener('change', function () {
		visibleGrantees().forEach(function (grantee) {
			grantee.checked = selectAll.checked;
		});
		updateSelection();
	});
	grantees.forEach(function (grantee) {
		grantee.addEventListener('change', updateSelection);
	});
	updateSelection();
});
