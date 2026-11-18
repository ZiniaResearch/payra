import { createFileRoute } from "@tanstack/react-router";
import { BuyCreditsPage } from "@/modules/billing/BuyCreditsPage";

export const Route = createFileRoute("/_protected/billing")({
	component: BuyCreditsPage,
});
