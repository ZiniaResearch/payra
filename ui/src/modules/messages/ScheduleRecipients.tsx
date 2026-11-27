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
	Pressable,
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
import {
	recipientSaveErrorMessage,
	useSaveRecipient,
	useUpdateRecipient,
} from "./mutations";
import { savedRecipientsQueryOptions } from "./queries";
import {
	compactRecipientPhone,
	localRecipientPhone,
	normalizeRecipientPhone,
	type RecipientFields,
	type RecipientId,
	type SavedRecipient,
} from "./recipient";
import { ScheduleRecipientEditor } from "./ScheduleRecipientEditor";

const RECIPIENTS_PER_PAGE = 10;
const EMPTY_RECIPIENTS: readonly SavedRecipient[] = [];
const ADD_RECIPIENT_KEY = "add-recipient";
const ROW_CLASS =
	"grid min-w-0 content-center rounded-lg px-2 py-1.5 outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-border-focus-ring";
const RECIPIENT_ACTION_CLASS =
	"bg-transparent text-text-primary hover:bg-transparent active:bg-transparent disabled:bg-transparent disabled:text-text-primary";
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

function RecipientRow({
	recipient,
	onEdit,
}: {
	recipient: SavedRecipient;
	onEdit: ((recipient: SavedRecipient) => void) | undefined;
}) {
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
					<Pressable isDisabled={!onEdit} onPress={() => onEdit?.(recipient)}>
						<Button
							variant="ghost"
							size="small"
							iconOnly
							leadingIcon={RiPencilLine}
							aria-label={`Edit ${label}`}
							className={cx(
								RECIPIENT_ACTION_CLASS,
								"hover:text-status-blue-text focus-visible:text-status-blue-text",
							)}
							disabled={!onEdit}
						/>
					</Pressable>
					<Button
						variant="ghost"
						size="small"
						iconOnly
						leadingIcon={RiDeleteBinLine}
						aria-label={`Delete ${label}`}
						className={cx(
							RECIPIENT_ACTION_CLASS,
							"hover:text-status-rose-text focus-visible:text-status-rose-text disabled:hover:text-status-rose-text",
						)}
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

type RecipientEditor =
	| { readonly kind: "add"; readonly phone: string }
	| { readonly kind: "edit"; readonly recipient: SavedRecipient };

/** Searches and saves contacts, and edits the schedule draft's selection. */
export function ScheduleRecipients({
	sessionId,
	selectedRecipients,
	setSelectedRecipients,
}: ScheduleRecipientsProps) {
	const savedRecipients = useQuery(savedRecipientsQueryOptions(sessionId));
	const saveRecipient = useSaveRecipient(sessionId);
	const updateRecipient = useUpdateRecipient(sessionId);
	const isSaving = saveRecipient.isPending || updateRecipient.isPending;
	const recipients = savedRecipients.data ?? EMPTY_RECIPIENTS;
	const recipientsById = useMemo(
		() =>
			new Map<string, SavedRecipient>(recipients.map((row) => [row.id, row])),
		[recipients],
	);
	const [editor, setEditor] = useState<RecipientEditor>();
	const draftPhone = editor?.kind === "add" ? editor.phone : undefined;
	const editedRecipient =
		editor?.kind === "edit" ? editor.recipient : undefined;
	const editorKey = draftPhone ? ADD_RECIPIENT_KEY : editedRecipient?.id;
	const editorError =
		editor &&
		(editor.kind === "edit" ? updateRecipient.error : saveRecipient.error);
	const saveError = editorError
		? recipientSaveErrorMessage(editorError)
		: undefined;
	const [query, setQuery] = useState("");
	const [requestedPage, setPage] = useState(1);
	const inputRef = useRef<HTMLInputElement>(null);
	const listRef = useRef<HTMLDivElement>(null);
	const listId = useId();

	const matches = useMemo(() => {
		const nameQuery = query.trim().toLocaleLowerCase();
		if (!nameQuery) return recipients;

		const phoneQuery = compactRecipientPhone(query);
		if (/^\+?[0-9]+$/.test(phoneQuery)) {
			// The normalized +880 number already ends in the local 01… number.
			return recipients.filter(({ phone }) => phone.includes(phoneQuery));
		}
		return recipients.filter(({ name }) =>
			name?.toLocaleLowerCase().includes(nameQuery),
		);
	}, [query, recipients]);

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
		if (isSaving) return;
		setPage(nextPage);
		setEditor(undefined);
	}

	function openNewRecipient() {
		if (!addPhone || draftPhone || isSaving) return;
		saveRecipient.reset();
		setPage(totalPages);
		setEditor({ kind: "add", phone: addPhone });
	}

	function openExistingRecipient(recipient: SavedRecipient) {
		updateRecipient.reset();
		setEditor({ kind: "edit", recipient });
	}

	function resetSearchAndFocus() {
		setQuery("");
		setPage(1);
		closeEditor();
	}

	function changeSelection(selection: Selection) {
		if (isSaving || selection === "all") return;
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
		setEditor(undefined);
		inputRef.current?.focus();
	}

	function saveNewRecipient(fields: RecipientFields) {
		saveRecipient.mutate(fields, {
			onSuccess: (saved) => {
				setSelectedRecipients((current) => new Set(current).add(saved.id));
				resetSearchAndFocus();
			},
		});
	}

	function saveEditedRecipient({ phone, name }: RecipientFields) {
		if (!editedRecipient) return;
		const phoneChanged = phone !== editedRecipient.phone;
		const nameChanged = name !== editedRecipient.name;
		if (!phoneChanged && !nameChanged) {
			closeEditor();
			return;
		}
		updateRecipient.mutate(
			{
				id: editedRecipient.id,
				changes: {
					...(phoneChanged ? { phone } : {}),
					...(nameChanged ? { name } : {}),
				},
			},
			{ onSuccess: closeEditor },
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
			<TextField value={query} onChange={changeQuery} isReadOnly={isSaving}>
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
				keyboardNavigationBehavior={editor ? "tab" : "arrow"}
				shouldSelectOnPressUp
				disabledBehavior="selection"
				disabledKeys={editorKey ? [editorKey] : []}
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
				{pageRecipients.map((recipient) =>
					recipient.id === editedRecipient?.id ? (
						<GridListItem
							key={recipient.id}
							id={recipient.id}
							focusMode="child"
							textValue={recipient.name ?? localRecipientPhone(recipient.phone)}
							className={ROW_CLASS}
						>
							<ScheduleRecipientEditor
								initialPhone={localRecipientPhone(editedRecipient.phone)}
								initialName={editedRecipient.name}
								isSaving={isSaving}
								saveError={saveError}
								onSave={saveEditedRecipient}
								onCancel={closeEditor}
							/>
						</GridListItem>
					) : (
						<RecipientRow
							key={recipient.id}
							recipient={recipient}
							onEdit={isSaving ? undefined : openExistingRecipient}
						/>
					),
				)}
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
								initialPhone={localRecipientPhone(draftPhone)}
								isSaving={isSaving}
								saveError={saveError}
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
