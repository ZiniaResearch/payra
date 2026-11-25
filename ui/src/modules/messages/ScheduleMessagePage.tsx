import { Button } from "@/components/base/buttons/button";
import { Divider } from "@/components/base/divider/divider";
import { Textarea } from "@/components/base/textarea/textarea";
import { ScheduleRecipients } from "@/modules/messages/ScheduleRecipients";
import { ScheduleSendTimes } from "@/modules/messages/ScheduleSendTimes";

export function ScheduleMessagePage() {
	return (
		<section
			aria-labelledby="schedule-message-title"
			className="flex min-h-full flex-col gap-6 pt-4 pe-4 pb-4 ps-6 sm:pt-6 sm:pe-6 sm:pb-6 sm:ps-10"
		>
			<header className="flex flex-col gap-3">
				<h1 id="schedule-message-title" className="text-title-2-medium">
					Schedule SMS
				</h1>
				<Divider />
			</header>

			<div className="grid gap-6 lg:grid-cols-2">
				<div className="flex min-w-0 flex-col gap-6">
					<Textarea
						label="Message"
						name="message"
						placeholder="Write your SMS here…"
						rows={4}
						autoResize
						maxRows={8}
						resize="vertical"
						showCount
					/>

					<ScheduleSendTimes />
				</div>

				<div className="min-w-0 lg:border-s lg:border-separator-border lg:ps-6">
					<ScheduleRecipients />
				</div>
			</div>

			<Button className="self-end" disabled>
				Schedule SMS
			</Button>
		</section>
	);
}
