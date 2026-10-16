(() => {
    const filters = Array.from(document.querySelectorAll('.course-catalog-filter'));
    if (!filters.length) return;

    filters.forEach(filter => {
        filter.addEventListener('toggle', () => {
            if (!filter.open) return;
            filters.forEach(other => {
                if (other !== filter) other.open = false;
            });
        });
    });

    document.addEventListener('click', event => {
        filters.forEach(filter => {
            if (filter.open && !filter.contains(event.target)) filter.open = false;
        });
    });

    document.addEventListener('keydown', event => {
        if (event.key !== 'Escape') return;
        const openFilter = filters.find(filter => filter.open);
        if (!openFilter) return;
        openFilter.open = false;
        openFilter.querySelector('summary').focus();
        event.preventDefault();
    });
})();
