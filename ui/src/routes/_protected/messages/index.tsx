import { createFileRoute } from "@tanstack/react-router";
import { MessagesPage } from "@/modules/messages/MessagesPage";

export const Route = createFileRoute("/_protected/messages/")({
	component: MessagesPage,
});
