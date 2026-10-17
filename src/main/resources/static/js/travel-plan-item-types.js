/* 타입의 표시 이름과 선 아이콘은 이 모듈에서만 정의한다. */
window.travelPlanItemTypes = Object.freeze({
    SCHEDULE: {label: "일정", path: "M8 3v4m8-4v4M4 10h16M5 5h14v16H5z"},
    FLIGHT: {label: "항공", path: "m3 13 7 1 7 7 2-1-4-8 6-6-2-2-6 6-8-4-1 2 7 7-1 7"},
    ACCOMMODATION: {label: "숙소", path: "M4 21V5h16v16M8 9h2m4 0h2M8 13h2m4 0h2M10 21v-4h4v4"},
    TRANSPORT: {label: "이동", path: "M5 17V7c0-4 14-4 14 0v10H5zm0-7h14M8 20l-2 2m10-2 2 2M8 14h1m6 0h1"},
    MEAL: {label: "식사", path: "M5 3v7m3-7v7m-6-7v7h6M5 10v11M17 3v18m0-18c-5 5-5 9 0 9"},
    SIGHTSEEING: {label: "관광", path: "M3 21h18M5 17h14M6 9v8m6-8v8m6-8v8M3 7l9-5 9 5H3z"},
    NOTE: {label: "메모", path: "M5 3h14v18H5zM8 8h8M8 12h8M8 16h5"}
});
window.decorateTravelPlanTypes = function(root = document) {
    root.querySelectorAll("[data-item-type-badge]").forEach(badge => {
        if (badge.querySelector("svg")) return;
        const type = window.travelPlanItemTypes[badge.dataset.itemTypeBadge] || window.travelPlanItemTypes.SCHEDULE;
        const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
        svg.setAttribute("viewBox", "0 0 24 24");
        svg.setAttribute("aria-hidden", "true");
        const path = document.createElementNS(svg.namespaceURI, "path");
        path.setAttribute("d", type.path);
        svg.append(path);
        badge.replaceChildren(svg, document.createTextNode(type.label));
    });
};
document.addEventListener("DOMContentLoaded", () => window.decorateTravelPlanTypes());
document.addEventListener("travelplan:schedule-updated", event => window.decorateTravelPlanTypes(event.detail?.root || document));
