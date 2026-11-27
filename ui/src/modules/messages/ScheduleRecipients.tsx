import { RiDeleteBinLine, RiPencilLine, RiSearchLine } from "@remixicon/react";
import { useQuery } from "@tanstack/react-query";
import {
	type Dispatch,
	type KeyboardEvent,
	type SetStateAction,
	useId,
	useMemo,
	useRef,
	useState,
} from "react";
import {
	GridList,
	GridListItem,
	Group,
	Input,
	type Selection,
} from "react-aria-components";
import { Avatar } from "@/components/base/avatar/avatar";
import { CHIP_COLORS, Chip } from "@/components/base/badges/chip";
import { Button } from "@/components/base/buttons/button";
import { CloseButton } from "@/components/base/buttons/close-button";
import {
	inputElementClassName,
	inputFieldClassName,
	TextField,
} from "@/components/base/input/input";
import { Label } from "@/components/base/input/label";
import { Pagination } from "@/components/base/pagination/pagination";
import { cx } from "@/utils/cx";
import { recipientSaveErrorMessage, useSaveRecipient } from "./mutations";
import { savedRecipientsQueryOptions } from "./queries";
import {
	compactRecipientPhone,
	localRecipientPhone,
	normalizeRecipientPhone,
	type RecipientId,
	type SavedRecipient,
} from "./recipient";
import { ScheduleRecipientEditor } from "./ScheduleRecipientEditor";

const RECIPIENTS_PER_PAGE = 10;
const EMPTY_RECIPIENTS: readonly SavedRecipient[] = [];
const ADD_RECIPIENT_KEY = "add-recipient";
const ROW_CLASS =
	"grid min-w-0 content-center rounded-lg px-2 py-1.5 outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-border-focus-ring";
const RECIPIENT_ACCENTS = [
	{
		tone: "blue",
		nameClassName: "text-status-blue-text",
		chipClassName: "bg-status-blue-background/75",
	},
	{
		tone: "purple",
		nameClassName: "text-status-purple-text",
		chipClassName: "bg-status-purple-background/75",
	},
	{
		tone: "cyan",
		nameClassName: "text-status-cyan-text",
		chipClassName: "bg-status-cyan-background/75",
	},
	{
		tone: "lime",
		nameClassName: "text-status-lime-text",
		chipClassName: "bg-status-lime-background/75",
	},
	{
		tone: "rose",
		nameClassName: "text-status-rose-text",
		chipClassName: "bg-status-rose-background/75",
	},
] as const;

function getRecipientAccent(id: string) {
	const suffix = Number.parseInt(id.slice(-4), 16);
	return (
		RECIPIENT_ACCENTS[suffix % RECIPIENT_ACCENTS.length] ?? RECIPIENT_ACCENTS[0]
	);
}

function RecipientRow({ recipient }: { recipient: SavedRecipient }) {
	const { id, name, phone } = recipient;
	const localPhone = localRecipientPhone(phone);
	const label = name ?? localPhone;
	const accent = getRecipientAccent(id);
	const initials = name
		?.split(/\s+/, 2)
		.map((word) => word[0])
		.join("")
		.toLocaleUpperCase();

	return (
		<GridListItem
			id={id}
			textValue={name ? `${name} ${localPhone}` : label}
			className={({ isSelected }) =>
				cx(
					ROW_CLASS,
					"cursor-pointer",
					isSelected
						? "bg-background-tertiary-hover/60 hover:bg-background-tertiary-hover/80"
						: "hover:bg-background-tertiary-hover/30",
				)
			}
		>
			<div className="flex min-h-8 items-center gap-2">
				<Avatar
					size="md"
					initials={initials ?? "#"}
					className={CHIP_COLORS[accent.tone]}
					aria-hidden
				/>
				<div className="flex min-w-0 flex-1 flex-col text-body-medium">
					{name && (
						<bdi className={cx("truncate", accent.nameClassName)}>{name}</bdi>
					)}
					<bdi
						dir="ltr"
						className={cx(
							"truncate",
							name && "text-body-2-regular text-text-secondary",
						)}
					>
						{localPhone}
					</bdi>
				</div>
				<div className="flex shrink-0 items-center gap-1">
					<Button
						variant="ghost"
						size="small"
						iconOnly
						leadingIcon={RiPencilLine}
						aria-label={`Edit ${label}`}
						className="disabled:bg-transparent"
						disabled
					/>
					<Button
						variant="ghost"
						size="small"
						iconOnly
						leadingIcon={RiDeleteBinLine}
						aria-label={`Delete ${label}`}
						className="disabled:bg-transparent"
						disabled
					/>
				</div>
			</div>
		</GridListItem>
	);
}

function SelectedRecipientChip({
	recipient,
	onRemove,
}: {
	recipient: SavedRecipient;
	onRemove: (id: RecipientId) => void;
}) {
	const { id, name, phone } = recipient;
	const localPhone = localRecipientPhone(phone);
	const label = name ?? localPhone;
	const accent = getRecipientAccent(id);

	return (
		<Chip
			color={accent.tone}
			variant="caption"
			className={cx(
				"min-h-8.5 max-w-full px-1 py-px whitespace-normal",
				accent.chipClassName,
				name ? "gap-1.5" : "gap-0.5",
			)}
		>
			<span className="flex min-w-0 flex-col items-start wrap-anywhere">
				<bdi dir={name ? undefined : "ltr"}>{label}</bdi>
				{name && (
					<bdi dir="ltr" className="text-caption-2-regular">
						{localPhone}
					</bdi>
				)}
			</span>
			<CloseButton
				size="sm"
				aria-label={`Remove ${label}`}
				className="rounded-md bg-transparent text-current hover:bg-current/10 hover:text-current focus-visible:bg-current/10"
				onClick={() => onRemove(id)}
			/>
		</Chip>
	);
}

interface ScheduleRecipientsProps {
	readonly sessionId: string;
	readonly selectedRecipients: ReadonlySet<RecipientId>;
	readonly setSelectedRecipients: Dispatch<
		SetStateAction<ReadonlySet<RecipientId>>
	>;
}

/** Searches and saves contacts, and edits the schedule draft's selection. */
export function ScheduleRecipients({
	sessionId,
	selectedRecipients,
	setSelectedRecipients,
}: ScheduleRecipientsProps) {
	const savedRecipients = useQuery(savedRecipientsQueryOptions(sessionId));
	const saveRecipient = useSaveRecipient(sessionId);
	const recipients = savedRecipients.data ?? EMPTY_RECIPIENTS;
	const recipientsById = useMemo(
		() =>
			new Map<string, SavedRecipient>(recipients.map((row) => [row.id, row])),
		[recipients],
	);
	const [draftPhone, setDraftPhone] = useState<string>();
	const [query, setQuery] = useState("");
	const [requestedPage, setPage] = useState(1);
	const inputRef = useRef<HTMLInputElement>(null);
	const listRef = useRef<HTMLDivElement>(null);
	const listId = useId();

	const phoneQuery = compactRecipientPhone(query);
	const isPhoneQuery = /^\+?[0-9]+$/.test(phoneQuery);
	const nameQuery = query.trim().toLocaleLowerCase();
	const matches = nameQuery
		? recipients.filter(({ phone, name }) =>
				isPhoneQuery
					? phone.includes(phoneQuery) ||
						localRecipientPhone(phone).includes(phoneQuery)
					: name?.toLocaleLowerCase().includes(nameQuery),
			)
		: recipients;

	const phoneToAdd = normalizeRecipientPhone(query);
	const addPhone =
		draftPhone ??
		(phoneToAdd &&
		savedRecipients.data &&
		!matches.some(({ phone }) => phone === phoneToAdd)
			? phoneToAdd
			: undefined);
	const totalPages = Math.ceil(
		(matches.length + (addPhone ? 1 : 0)) / RECIPIENTS_PER_PAGE,
	);
	const page = Math.min(requestedPage, Math.max(totalPages, 1));
	const pageStart = (page - 1) * RECIPIENTS_PER_PAGE;
	const pageRecipients = matches.slice(
		pageStart,
		pageStart + RECIPIENTS_PER_PAGE,
	);

	function changeQuery(value: string) {
		setQuery(value);
		changePage(1);
	}

	function changePage(nextPage: number) {
		if (saveRecipient.isPending) return;
		setPage(nextPage);
		setDraftPhone(undefined);
	}

	function openNewRecipient() {
		if (!addPhone || draftPhone) return;
		saveRecipient.reset();
		setPage(totalPages);
		setDraftPhone(addPhone);
	}

	function resetSearchAndFocus() {
		setQuery("");
		setPage(1);
		setDraftPhone(undefined);
		inputRef.current?.focus();
	}

	function changeSelection(selection: Selection) {
		if (saveRecipient.isPending || selection === "all") return;
		if (selection.has(ADD_RECIPIENT_KEY)) {
			openNewRecipient();
			return;
		}
		const next = new Set<RecipientId>();
		for (const key of selection) {
			const recipient =
				typeof key === "string" ? recipientsById.get(key) : undefined;
			if (recipient) next.add(recipient.id);
		}
		setSelectedRecipients(next);
		if (query && next.size > selectedRecipients.size) {
			resetSearchAndFocus();
		}
	}

	function removeRecipient(id: RecipientId) {
		setSelectedRecipients((current) => {
			if (!current.has(id)) return current;
			const next = new Set(current);
			next.delete(id);
			return next;
		});
		inputRef.current?.focus();
	}

	function clearRecipients() {
		setSelectedRecipients(new Set());
		inputRef.current?.focus();
	}

	function closeEditor() {
		setDraftPhone(undefined);
		inputRef.current?.focus();
	}

	function saveNewRecipient(name: string, phone: string) {
		const normalizedPhone = normalizeRecipientPhone(phone);
		if (!normalizedPhone) {
			return "Enter a valid Bangladeshi mobile number.";
		}
		saveRecipient.mutate(
			{ phone: normalizedPhone, name: name.trim() || null },
			{
				onSuccess: (saved) => {
					setSelectedRecipients((current) => new Set(current).add(saved.id));
					resetSearchAndFocus();
				},
			},
		);
	}

	function handleSearchKeyDown(event: KeyboardEvent<HTMLInputElement>) {
		if (event.key === "Enter" && addPhone && !event.nativeEvent.isComposing) {
			event.preventDefault();
			openNewRecipient();
			return;
		}
		if (event.key !== "ArrowDown" && event.key !== "ArrowUp") return;
		const rows = listRef.current?.querySelectorAll<HTMLElement>(
			"[role=row][aria-selected]",
		);
		const row = event.key === "ArrowDown" ? rows?.[0] : rows?.[rows.length - 1];
		if (!row) return;
		event.preventDefault();
		row.focus();
	}

	return (
		<div className="flex flex-col gap-2">
			<TextField
				value={query}
				onChange={changeQuery}
				isReadOnly={saveRecipient.isPending}
			>
				<Label>Recipients</Label>
				<Group
					className={(state) =>
						cx(inputFieldClassName(state), "flex-col items-stretch gap-1")
					}
				>
					{selectedRecipients.size > 0 && (
						<div className="flex min-w-0 flex-wrap items-center gap-1">
							{[...selectedRecipients].map((id) => {
								const recipient = recipientsById.get(id);
								return recipient ? (
									<SelectedRecipientChip
										key={id}
										recipient={recipient}
										onRemove={removeRecipient}
									/>
								) : null;
							})}
							<Button
								variant="secondary"
								size="xs"
								className="h-8.5 rounded-md px-1.5"
								onClick={clearRecipients}
							>
								Clear All
							</Button>
						</div>
					)}
					<div className="flex min-w-0 items-center gap-1.5">
						<RiSearchLine className="size-5 shrink-0" aria-hidden />
						<Input
							ref={inputRef}
							aria-controls={listId}
							placeholder={
								selectedRecipients.size
									? "Search or add a number"
									: "Search by name or phone number"
							}
							className={cx(inputElementClassName, "h-8 ps-1 text-start")}
							onKeyDown={handleSearchKeyDown}
						/>
					</div>
				</Group>
			</TextField>
			<GridList
				ref={listRef}
				id={listId}
				aria-label="Saved recipients"
				selectionMode="multiple"
				keyboardNavigationBehavior={draftPhone ? "tab" : "arrow"}
				shouldSelectOnPressUp
				disabledBehavior="selection"
				disabledKeys={draftPhone ? [ADD_RECIPIENT_KEY] : []}
				selectedKeys={selectedRecipients}
				onSelectionChange={changeSelection}
				className="grid gap-1 rounded-xl border border-border-button-default bg-background-primary-default p-1 outline-none"
				style={{
					gridTemplateRows: `repeat(${RECIPIENTS_PER_PAGE}, minmax(calc(var(--spacing) * 13), max-content))`,
				}}
				renderEmptyState={() =>
					savedRecipients.isError ? (
						<div className="flex flex-wrap items-center gap-2 p-2">
							<p
								role="alert"
								className="text-body-2-regular text-text-error-primary"
							>
								Could not load saved recipients.
							</p>
							<Button
								variant="secondary"
								size="xs"
								disabled={savedRecipients.isFetching}
								onClick={() => void savedRecipients.refetch()}
							>
								{savedRecipients.isFetching ? "Retrying…" : "Try again"}
							</Button>
						</div>
					) : (
						<output className="block p-2 text-body-2-regular text-text-secondary">
							{savedRecipients.isPending
								? "Loading saved recipients…"
								: query.trim()
									? "No matching recipients"
									: "Type a mobile number to add a recipient."}
						</output>
					)
				}
			>
				{pageRecipients.map((recipient) => (
					<RecipientRow key={recipient.id} recipient={recipient} />
				))}
				{addPhone && page === totalPages && (
					<GridListItem
						id={ADD_RECIPIENT_KEY}
						focusMode={draftPhone ? "child" : "row"}
						textValue={`Add ${localRecipientPhone(addPhone)}`}
						className={cx(
							ROW_CLASS,
							!draftPhone &&
								"cursor-pointer hover:bg-background-tertiary-hover/30",
						)}
					>
						{draftPhone ? (
							<ScheduleRecipientEditor
								phone={localRecipientPhone(draftPhone)}
								isSaving={saveRecipient.isPending}
								saveError={
									saveRecipient.error
										? recipientSaveErrorMessage(saveRecipient.error)
										: undefined
								}
								onSave={saveNewRecipient}
								onCancel={closeEditor}
							/>
						) : (
							<div className="flex min-h-8 items-center gap-2 text-body-medium">
								<Avatar size="md" initials="#" aria-hidden />
								<span>
									Add <bdi dir="ltr">{localRecipientPhone(addPhone)}</bdi>
								</span>
							</div>
						)}
					</GridListItem>
				)}
			</GridList>
			<Pagination
				aria-label="Recipient pages"
				page={page}
				totalPages={totalPages}
				onChange={changePage}
			/>
		</div>
	);
}
