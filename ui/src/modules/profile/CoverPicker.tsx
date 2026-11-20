import { RiEditLine } from "@remixicon/react";
import { useQuery } from "@tanstack/react-query";
import { useState, useSyncExternalStore } from "react";
import { Button } from "@/components/base/buttons/button";
import {
	Dropdown,
	DropdownIconTrigger,
	DropdownPopover,
} from "@/components/base/dropdown/dropdown";
import { Skeleton } from "@/components/base/skeleton/skeleton";
import {
	ScrollFade,
	useScrollFade,
} from "@/components/foundations/scroll-fade/scroll-fade";
import { useUpdateProfileCover } from "./mutations";
import {
	type CoverPreset,
	type CurrentAccount,
	coverPresetsQueryOptions,
} from "./queries";

const sidePlacementQuery = "(min-width: 63rem)";

function subscribeToSidePlacement(onChange: () => void) {
	const query = window.matchMedia(sidePlacementQuery);
	query.addEventListener("change", onChange);
	return () => query.removeEventListener("change", onChange);
}

export function CoverPicker({ account }: { account?: CurrentAccount }) {
	const [isOpen, setIsOpen] = useState(false);
	const [draft, setDraft] = useState<CoverPreset | null>(null);
	const covers = useQuery({ ...coverPresetsQueryOptions(), enabled: isOpen });
	const update = useUpdateProfileCover();
	const fade = useScrollFade();
	const canPlaceBesideCover = useSyncExternalStore(
		subscribeToSidePlacement,
		() => window.matchMedia(sidePlacementQuery).matches,
		() => false,
	);
	const coverUrl = draft?.url ?? account?.cover_url;
	const savedCoverId = account?.cover_id;
	const selectedId = draft?.id ?? savedCoverId;

	function closePicker() {
		setDraft(null);
		update.reset();
		setIsOpen(false);
	}

	function changeOpen(open: boolean) {
		if (open) {
			setIsOpen(true);
		} else if (!update.isPending) {
			closePicker();
		}
	}

	return (
		<>
			<div
				aria-hidden
				className="h-40 bg-background-tertiary-default bg-cover bg-center"
				style={{
					backgroundImage: coverUrl
						? `url(${JSON.stringify(coverUrl)})`
						: undefined,
				}}
			/>
			<Dropdown isOpen={isOpen} onOpenChange={changeOpen}>
				<DropdownIconTrigger
					icon={RiEditLine}
					size="small"
					aria-label="Change cover image"
					disabled={!account}
					className="absolute bottom-3 end-3"
				/>
				<DropdownPopover
					aria-label="Cover presets"
					placement={canPlaceBesideCover ? "end top" : "bottom end"}
					offset={8}
					className="flex w-60 flex-col overflow-hidden p-1.5 min-[63rem]:w-44 min-[68rem]:w-52 min-[72rem]:w-60"
					dialogClassName="min-h-0 gap-2"
				>
					{covers.isPending ? (
						<output aria-busy="true" className="block p-1 text-body-regular">
							<Skeleton className="mb-2 aspect-video w-full" />
							Loading covers…
						</output>
					) : covers.isError ? (
						<div className="flex flex-col items-start gap-2 p-1">
							<p
								role="alert"
								className="text-body-regular text-text-error-primary"
							>
								Could not load covers.
							</p>
							<Button
								variant="secondary"
								size="small"
								disabled={covers.isFetching}
								onClick={() => void covers.refetch()}
							>
								{covers.isFetching ? "Retrying…" : "Try again"}
							</Button>
						</div>
					) : (
						<div className="relative flex min-h-0 flex-col">
							<div
								onScroll={fade.onScroll}
								className="grid min-h-0 gap-1.5 overflow-y-auto overscroll-contain"
							>
								{covers.data.map((cover) => (
									<Button
										key={cover.id}
										variant="secondary"
										aria-label={
											cover.id === savedCoverId
												? `${cover.label}, current cover`
												: cover.label
										}
										aria-pressed={selectedId === cover.id}
										className="h-auto w-full rounded-lg border-2 p-0 focus-visible:ring-inset aria-pressed:border-button-primary [&>span]:w-full [&>span]:px-0"
										disabled={update.isPending}
										onClick={() => {
											setDraft(cover.id === savedCoverId ? null : cover);
											update.reset();
										}}
									>
										<span className="flex min-w-0 flex-1 flex-col items-center">
											<img
												src={cover.url}
												alt=""
												className="aspect-video w-full bg-background-tertiary-default object-cover"
											/>
											{cover.id === savedCoverId && (
												<span className="py-0.5 text-caption-1-semibold">
													Current
												</span>
											)}
										</span>
									</Button>
								))}
							</div>
							<ScrollFade
								progress={fade.progress}
								surfaceClassName="from-background-primary-default"
							/>
						</div>
					)}
					{update.isError && (
						<p
							role="alert"
							className="px-1 text-body-regular text-text-error-primary"
						>
							Could not save your cover. Try again.
						</p>
					)}
					<div className="flex shrink-0 flex-wrap justify-end gap-1.5">
						<Button
							variant="secondary"
							size="small"
							disabled={update.isPending}
							onClick={closePicker}
						>
							Cancel
						</Button>
						<Button
							size="small"
							disabled={!covers.isSuccess || !draft || update.isPending}
							onClick={() => {
								if (draft) {
									update.mutate(draft.id, { onSuccess: closePicker });
								}
							}}
						>
							{update.isPending ? "Saving…" : "Save cover"}
						</Button>
					</div>
				</DropdownPopover>
			</Dropdown>
		</>
	);
}
