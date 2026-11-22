/**
 * KHATAWAS — Editorial Landing Page Interactions
 * Lightweight, accessible, zero-dependency progressive enhancement
 */

document.addEventListener('DOMContentLoaded', () => {
  // 1. Intersection Observer for Smooth Scroll Reveals
  const revealElements = document.querySelectorAll('.reveal');
  
  if ('IntersectionObserver' in window) {
    const observer = new IntersectionObserver((entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          entry.target.classList.add('is-visible');
        }
      });
    }, {
      root: null,
      threshold: 0.05,
      rootMargin: '0px 0px 100px 0px'
    });

    revealElements.forEach((el) => observer.observe(el));
    
    // Safety check for full-page screenshots & non-scrolling viewports
    setTimeout(() => {
      revealElements.forEach(el => el.classList.add('is-visible'));
    }, 400);
  } else {
    // Fallback if observer not supported
    revealElements.forEach((el) => el.classList.add('is-visible'));
  }

  // 2. Smooth Navigation Links
  const anchorLinks = document.querySelectorAll('a[href^="#"]');
  anchorLinks.forEach((anchor) => {
    anchor.addEventListener('click', (e) => {
      const targetId = anchor.getAttribute('href');
      if (targetId === '#') return;
      
      const targetEl = document.querySelector(targetId);
      if (targetEl) {
        e.preventDefault();
        targetEl.scrollIntoView({
          behavior: 'smooth',
          block: 'start'
        });
        targetEl.focus({ preventScroll: true });
      }
    });
  });

  // 3. Act 4: Interactive Language Parsing Showcase
  const sampleData = {
    hi: {
      spoken: '“रमेश का 340 रुपये उधार लिखो”',
      customer: 'Ramesh (रमेश)',
      type: 'Udhaar (Credit)',
      amount: '₹340.00',
      action: 'Booked to Ramesh • Due updated',
      langCode: 'hi-IN'
    },
    kn: {
      spoken: '“ರಮೇಶ್ ಅವರಿಗೆ 340 ರೂಪಾಯಿ ಸಾಲ ಕೊಡಿ”',
      customer: 'Ramesh (ರಮೇಶ್)',
      type: 'Udhaar (Credit)',
      amount: '₹340.00',
      action: 'Booked to Ramesh • Due updated',
      langCode: 'kn-IN'
    },
    hinglish: {
      spoken: '“Ramesh ko 200 diya kal baki clear”',
      customer: 'Ramesh (रमेश)',
      type: 'Jama (Payment)',
      amount: '₹200.00',
      action: 'Payment credited • Balance reduced',
      langCode: 'hi-Latn'
    }
  };

  const tabButtons = document.querySelectorAll('.tab-btn');
  const spokenTextEl = document.getElementById('demoSpokenText');
  const customerNameEl = document.getElementById('demoCustomerName');
  const transactionTypeEl = document.getElementById('demoTransactionType');
  const amountNumberEl = document.getElementById('demoAmountNumber');
  const actionStatusEl = document.getElementById('demoActionStatus');

  tabButtons.forEach((btn) => {
    btn.addEventListener('click', () => {
      const lang = btn.dataset.lang;
      const data = sampleData[lang];
      if (!data) return;

      // Update active state
      tabButtons.forEach((b) => b.classList.remove('is-active'));
      tabButtons.forEach((b) => b.setAttribute('aria-selected', 'false'));
      btn.classList.add('is-active');
      btn.setAttribute('aria-selected', 'true');

      // Update demo stage with gentle transition
      if (spokenTextEl) spokenTextEl.textContent = data.spoken;
      if (customerNameEl) customerNameEl.textContent = data.customer;
      if (transactionTypeEl) {
        transactionTypeEl.textContent = data.type;
        if (data.type.includes('Payment')) {
          transactionTypeEl.style.color = 'var(--success)';
          transactionTypeEl.style.background = 'var(--success-soft)';
        } else {
          transactionTypeEl.style.color = 'var(--danger)';
          transactionTypeEl.style.background = 'var(--danger-soft)';
        }
      }
      if (amountNumberEl) {
        amountNumberEl.textContent = data.amount;
        amountNumberEl.style.color = data.type.includes('Payment') ? 'var(--success)' : 'var(--danger)';
      }
      if (actionStatusEl) actionStatusEl.textContent = data.action;
    });
  });

  // 4. Keyboard Navigation Enhancements
  const cards = document.querySelectorAll('.destination-card');
  cards.forEach((card) => {
    card.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') {
        card.click();
      }
    });
  });

  // Log clean status
  console.log('KHATAWAS Story-Driven Landing Page initialized.');
});
