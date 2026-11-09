$(function () {
    const form = $(".register-container form");
    if (!form.length) return;

    const availability = {email: false, nickname: false};
    const requestVersion = {email: 0, nickname: 0};
    const passwordPattern = /^(?=.*[!@#$%^&*])[A-Za-z\d!@#$%^&*]{8,72}$/;
    const emailPattern = /^[A-Z0-9._%+-]+@[A-Z0-9-]+(?:\.[A-Z0-9-]+)+$/i;

    /* 화면 문구는 서버 messages 번들이 source of truth 다. data-* 로 현재 locale 값을 받는다. */
    const messages = form.get(0).dataset;
    const progress = $(".registration-progress").get(0)?.dataset || {};
    const stepNames = ["", progress.stepName1 || "", progress.stepName2 || ""];
    const formatMessage = (template, ...values) => values.reduce(
        (text, value, index) => text.split("{" + index + "}").join(String(value)),
        template || "");
    const serverErrorSelectors = {
        userEmail: "#emailServerError",
        nickname: "#nicknameServerError",
        birthDate: "#birthDateServerError",
        agreedPolicyVersionIds: "[data-field-error='agreedPolicyVersionIds']"
    };
    const feedbackOwners = {
        "#emailMessage": "#userEmail",
        "#passwordValidationMessage": "#userPassword",
        "#passwordMessage": "#passwordConfirm",
        "#nicknameMessage": "#nickname",
        "#birthDateMessage": "#birthDate"
    };
    /* 통과했을 때 되돌릴 기본 안내. 서버가 내려준 현재 locale 문구다. */
    const birthDateHelpText = $("#birthDateMessage").text();
    let currentStep = initialStep();
    let suggestedEmail = "";
    let emailDomainOptions = [];
    let activeEmailDomainIndex = -1;
    let isSubmitting = false;

    function debounce(callback, delay = 300) {
        let timer;
        return function (...args) {
            clearTimeout(timer);
            timer = setTimeout(() => callback.apply(this, args), delay);
        };
    }

    function initialStep() {
        const field = $("[data-field-error]").first().data("field-error");
        if (field === "birthDate" || field === "agreedPolicyVersionIds") return 1;
        if (field || $("[data-server-error]").length) return 2;
        return 1;
    }

    function showStep(step) {
        currentStep = step;
        $(".form-step").prop("hidden", true).removeClass("is-active");
        $("#step-" + step).prop("hidden", false).addClass("is-active");
        $("[data-step-indicator]").each(function () {
            const indicatorStep = Number($(this).data("step-indicator"));
            $(this).toggleClass("is-complete", indicatorStep < step)
                .toggleClass("is-current", indicatorStep === step)
                .attr("aria-current", indicatorStep === step ? "step" : null);
        });
        $("#registrationStepStatus").text(
            formatMessage(progress.stepStatusFormat, 2, step, stepNames[step]));
        updateButtons();
        window.scrollTo({top: 0, behavior: "smooth"});
    }

    function setMessage(selector, message, type = "") {
        $(selector).text(message).removeClass("error success").addClass(type);
        if (feedbackOwners[selector]) {
            $(feedbackOwners[selector]).attr("aria-invalid", String(type === "error"));
        }
    }

    /* 만 14세 판정은 소셜 가입 화면과 같은 age-eligibility.js 를 쓴다.
       기준일은 브라우저가 아니라 서버가 내려준 data-today 다. */
    function birthDateStatus() {
        const field = $("#birthDate");
        if (field.length === 0) return "ok";
        return (window.TripBoraAgeEligibility || window.TravelDiaryAgeEligibility) // legacy fallback (P10a 제거)
            .status(field.val(), field.data("today"));
    }

    function renderBirthDateStatus(status) {
        if ($("#birthDate").length === 0) return;
        if (status === "underage") {
            setMessage("#birthDateMessage", messages.msgBirthdateUnderage, "error");
        } else if (status === "invalid") {
            setMessage("#birthDateMessage", messages.msgBirthdateInvalid, "error");
        } else {
            // 비어 있는 동안에는 오류가 아니라 원래 안내로 되돌린다.
            setMessage("#birthDateMessage", birthDateHelpText);
        }
    }

    function passwordIsValid() {
        return passwordPattern.test($("#userPassword").val());
    }

    function passwordsMatch() {
        const password = $("#userPassword").val();
        return password.length > 0 && password === $("#passwordConfirm").val();
    }

    // 어떤 항목이 필수인지는 서버가 정책 세트를 보고 data-policy-required 로 내려준다.
    // 활성화한 정책이 없으면 대상 체크박스도 없어 이 단계가 통과된다.
    function requiredPoliciesAccepted() {
        return $("[data-policy-consent][data-policy-required='true']")
            .filter(":not(:checked)").length === 0;
    }

    function updateButtons() {
        const requiredTermsAccepted = requiredPoliciesAccepted();
        // 가입 대상이 아닌 사용자를 다음 단계로 보내지 않는다. 약관과 연령을 모두 만족해야 한다.
        const birthDateAccepted = birthDateStatus() === "ok";
        $("#step1-next").prop("disabled", !requiredTermsAccepted || !birthDateAccepted);

        const accountReady = availability.email
            && passwordIsValid()
            && passwordsMatch()
            && availability.nickname;
        $("#step2-submit").prop("disabled", isSubmitting || !accountReady);
    }

    $("#birthDate").on("input change", function () {
        clearServerError("birthDate");
        // 고쳐서 만 14세 이상이 되면 오류가 그 자리에서 풀린다.
        renderBirthDateStatus(birthDateStatus());
        updateButtons();
    });

    function invalidate(field) {
        availability[field] = false;
        requestVersion[field] += 1;
        updateButtons();
    }

    function clearServerError(field) {
        $(serverErrorSelectors[field]).prop("hidden", true);
    }

    const checkEmailAvailability = debounce(function () {
        const email = $("#userEmail").val().trim().toLowerCase();
        if (!emailPattern.test(email)) return;
        const version = requestVersion.email;

        $.get("/api/users/check-email", {email})
            .done(function (response) {
                if (version !== requestVersion.email
                    || email !== $("#userEmail").val().trim().toLowerCase()) return;
                availability.email = response.valid !== false && !response.exists;
                if (response.valid === false) {
                    setMessage("#emailMessage", messages.msgEmailInvalid, "error");
                } else {
                    setMessage("#emailMessage",
                        response.exists ? messages.msgEmailTaken : messages.msgEmailAvailable,
                        response.exists ? "error" : "success");
                }
                updateButtons();
            })
            .fail(function () {
                if (version !== requestVersion.email) return;
                setMessage("#emailMessage", messages.msgEmailCheckFailed, "error");
            });
    });

    function updateEmailTypoSuggestion(email) {
        suggestedEmail = (window.TripBoraEmailDomain || window.TravelDiaryEmailDomain) // legacy fallback (P10a 제거)
            ?.suggest(email) || "";
        $("#emailSuggestion").prop("hidden", !suggestedEmail);
        if (suggestedEmail) {
            $("#emailSuggestionText").text(
                formatMessage(messages.msgEmailSuggestion, suggestedEmail));
        }
    }

    function renderEmailDomainOptions(email) {
        emailDomainOptions = (window.TripBoraEmailDomain || window.TravelDiaryEmailDomain) // legacy fallback (P10a 제거)
            ?.autocomplete(email) || [];
        activeEmailDomainIndex = -1;
        const list = $("#emailDomainSuggestions").empty();
        emailDomainOptions.forEach(function (option, index) {
            $("<button>", {
                type: "button",
                id: "email-domain-option-" + index,
                role: "option",
                text: option,
                "aria-selected": "false"
            }).attr("data-value", option).appendTo(list);
        });
        const visible = emailDomainOptions.length > 0;
        list.prop("hidden", !visible);
        $("#userEmail").attr("aria-expanded", String(visible)).removeAttr("aria-activedescendant");
    }

    function closeEmailDomainOptions() {
        emailDomainOptions = [];
        activeEmailDomainIndex = -1;
        $("#emailDomainSuggestions").empty().prop("hidden", true);
        $("#userEmail").attr("aria-expanded", "false").removeAttr("aria-activedescendant");
    }

    function moveEmailDomainSelection(direction) {
        if (!emailDomainOptions.length) return;
        activeEmailDomainIndex = (activeEmailDomainIndex + direction + emailDomainOptions.length)
            % emailDomainOptions.length;
        $("#emailDomainSuggestions [role='option']").each(function (index) {
            $(this).attr("aria-selected", String(index === activeEmailDomainIndex));
        });
        const activeId = "email-domain-option-" + activeEmailDomainIndex;
        $("#userEmail").attr("aria-activedescendant", activeId);
        document.getElementById(activeId)?.scrollIntoView({block: "nearest"});
    }

    function chooseEmailDomain(value) {
        if (!value) return;
        closeEmailDomainOptions();
        $("#userEmail").val(value).trigger("input").focus();
    }

    $("#agreeAll").on("change", function () {
        $("[data-policy-consent]").prop("checked", this.checked);
        updateButtons();
    });

    $(document).on("change", "[data-policy-consent]", function () {
        const consents = $("[data-policy-consent]");
        $("#agreeAll").prop("checked",
            consents.length > 0 && consents.filter(":not(:checked)").length === 0);
        clearServerError("agreedPolicyVersionIds");
        updateButtons();
    });

    $(".term-toggle").on("click", function () {
        const panel = $("#" + $(this).attr("aria-controls"));
        const expanded = $(this).attr("aria-expanded") === "true";
        $(this).attr("aria-expanded", String(!expanded))
            .text(expanded ? messages.msgTermsView : messages.msgTermsHide);
        panel.prop("hidden", expanded);
    });

    $("#userEmail").on("input", function () {
        clearServerError("userEmail");
        invalidate("email");
        const email = this.value.trim();
        updateEmailTypoSuggestion(email);
        renderEmailDomainOptions(email);
        if (!emailPattern.test(email)) {
            setMessage("#emailMessage", messages.msgEmailInvalid, "error");
            return;
        }
        setMessage("#emailMessage", messages.msgChecking);
        checkEmailAvailability();
    }).on("keydown", function (event) {
        if (event.key === "ArrowDown" || event.key === "ArrowUp") {
            if (!emailDomainOptions.length) return;
            event.preventDefault();
            moveEmailDomainSelection(event.key === "ArrowDown" ? 1 : -1);
        } else if (event.key === "Enter" && activeEmailDomainIndex >= 0) {
            event.preventDefault();
            chooseEmailDomain(emailDomainOptions[activeEmailDomainIndex]);
        } else if (event.key === "Escape") {
            closeEmailDomainOptions();
        }
    }).on("blur", function () {
        window.setTimeout(closeEmailDomainOptions, 120);
    });

    $("#emailDomainSuggestions")
        .on("pointerdown", "[role='option']", event => event.preventDefault())
        .on("click", "[role='option']", function () {
            chooseEmailDomain($(this).attr("data-value"));
        });

    $("#applyEmailSuggestion").on("click", function () {
        chooseEmailDomain(suggestedEmail);
    });

    const nicknameAvailability =
        window.TripBoraNicknameAvailability || window.TravelDiaryNicknameAvailability; // legacy fallback (P10a 제거)
    nicknameAvailability.initialize({
        onInput: () => clearServerError("nickname"),
        onAvailabilityChange: isAvailable => {
            availability.nickname = isAvailable;
            updateButtons();
        }
    });

    $("#userPassword, #passwordConfirm").on("input", function () {
        setMessage("#passwordValidationMessage",
            passwordIsValid() ? messages.msgPasswordValid : messages.msgPasswordInvalid,
            passwordIsValid() ? "success" : "error");
        if ($("#passwordConfirm").val().length > 0) {
            setMessage("#passwordMessage",
                passwordsMatch() ? messages.msgPasswordMatch : messages.msgPasswordMismatch,
                passwordsMatch() ? "success" : "error");
        } else {
            setMessage("#passwordMessage", "");
        }
        updateButtons();
    });

    $(".next-step").on("click", function () {
        if (this.disabled) return;
        // 버튼은 이미 비활성이지만, 값이 바뀐 직후를 대비해 넘어가기 직전에 한 번 더 본다.
        const status = birthDateStatus();
        if (status !== "ok") {
            renderBirthDateStatus(status);
            updateButtons();
            $("#birthDate").trigger("focus");
            return;
        }
        showStep(Math.min(2, currentStep + 1));
    });
    $(".prev-step").on("click", () => showStep(Math.max(1, currentStep - 1)));

    $(document).on("click", ".toggle-password", function () {
        const input = $($(this).data("toggle"));
        const reveal = input.attr("type") === "password";
        input.attr("type", reveal ? "text" : "password");
        // 표시/숨김 라벨은 버튼이 data-* 로 현재 locale 문구를 들고 있다.
        $(this).toggleClass("show", reveal).toggleClass("hide", !reveal)
            .attr("aria-label", reveal ? $(this).data("hide-label") : $(this).data("show-label"));
    });

    form.on("submit", function (event) {
        if (isSubmitting) {
            event.preventDefault();
            return;
        }
        if (!availability.email || !availability.nickname
            || !passwordIsValid() || !passwordsMatch()) {
            event.preventDefault();
            setMessage("#nicknameMessage", messages.msgIncomplete, "error");
            showStep(2);
            return;
        }
        $("#userEmail").val($("#userEmail").val().trim().toLowerCase());
        $("#nickname").val($("#nickname").val().trim());
        isSubmitting = true;
        $("#step2-submit").text($("#step2-submit").data("submitting-label"));
        updateButtons();
    });

    showStep(currentStep);
    $("#userEmail, #nickname").each(function () {
        const field = this.id;
        if (this.value.trim() && !$(serverErrorSelectors[field]).length) $(this).trigger("input");
    });
    window.addEventListener("pageshow", function () {
        isSubmitting = false;
        $("#step2-submit").text($("#step2-submit").data("submit-label"));
        updateButtons();
    });
});
