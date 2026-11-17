import { createFileRoute } from "@tanstack/react-router";
import { AnalyticsPage } from "@/modules/analytics/AnalyticsPage";

export const Route = createFileRoute("/_protected/analytics")({
	component: AnalyticsPage,
});
