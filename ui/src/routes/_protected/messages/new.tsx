import { createFileRoute } from "@tanstack/react-router";
import { ScheduleMessagePage } from "@/modules/messages/ScheduleMessagePage";

export const Route = createFileRoute("/_protected/messages/new")({
	component: ScheduleMessagePage,
});
